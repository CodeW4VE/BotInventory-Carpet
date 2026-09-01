package froyln.botinventory;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.function.IntPredicate;

import static net.minecraft.commands.Commands.literal;

public class ViewCommand {
    /**
     * Real usable player inventory slots: 36 main+hotbar, 4 armor, 1 offhand.
     * NOT the same as Inventory#getContainerSize(), which is 43 in 1.21.11 - it
     * also counts the BODY and SADDLE equipment slots (indices 41/42), added
     * for other entity types but present in every player's slot map too.
     * Those aren't meaningful for a player and shouldn't be shown as editable.
     */
    private static final int DISPLAYED_INVENTORY_SIZE = 41;

    private static final SimpleCommandExceptionType OFFLINE_VIEWING_DISABLED =
        new SimpleCommandExceptionType(Component.literal("Player not online, and offline viewing is disabled (viewOfflinePlayerInventory rule)"));
    private static final SimpleCommandExceptionType NO_SAVED_DATA =
        new SimpleCommandExceptionType(Component.literal("Player not online, and has no saved data (never joined this server)"));
    private static final SimpleCommandExceptionType NOT_ALLOWED_REAL_PLAYER =
        new SimpleCommandExceptionType(Component.literal("Not allowed to view a real player's inventory"));
    private static final SimpleCommandExceptionType ALREADY_OPEN =
        new SimpleCommandExceptionType(Component.literal("Someone else is already viewing this player's saved data"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var playerNode = dispatcher.getRoot().getChild("player");
        if (playerNode == null) return;

        var playerArgNode = playerNode.getChild("player");
        if (playerArgNode == null) return;

        var viewNode = literal("view")
            .then(literal("inventory")
                .requires(source -> isViewAllowed(source, BotInventoryRules.viewPlayerInventoryCommand))
                .executes(ViewCommand::viewInventory))
            .then(literal("enderchest")
                .requires(source -> isViewAllowed(source, BotInventoryRules.viewPlayerEnderchestCommand))
                .executes(ViewCommand::viewEnderchest))
            .build();

        playerArgNode.addChild(viewNode);
    }

    /** Also used directly by the right-click mixin (bot-only, always online) via `player.createCommandSourceStack()`. */
    public static boolean isViewAllowed(CommandSourceStack source, String ruleValue) {
        return checkRule(ruleValue, level -> checkPermissionLevel(source, level));
    }

    private static boolean checkPermissionLevel(CommandSourceStack source, int level) {
        PermissionCheck check = switch (level) {
            case 0 -> Commands.LEVEL_ALL;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 2 -> Commands.LEVEL_GAMEMASTERS;
            case 3 -> Commands.LEVEL_ADMINS;
            case 4 -> Commands.LEVEL_OWNERS;
            default -> null;
        };
        return check != null && check.check(source.permissions());
    }

    private static boolean checkRule(String ruleValue, IntPredicate permCheck) {
        return switch (ruleValue) {
            case "true" -> true;
            case "false" -> false;
            case "ops" -> permCheck.test(2);
            default -> {
                try {
                    yield permCheck.test(Integer.parseInt(ruleValue));
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    /** Opens the online, live-redirect inventory GUI. Also used directly by the right-click mixin (bot-only, always online). */
    public static void openInventory(ServerPlayer player, ServerPlayer targetPlayer) {
        OnlineViewGui gui = new OnlineViewGui(MenuType.GENERIC_9x5, player, targetPlayer.getUUID());
        gui.setTitle(targetPlayer.getName());
        redirectGrid(gui, targetPlayer.getInventory(), DISPLAYED_INVENTORY_SIZE);
        fillBarrier(gui, DISPLAYED_INVENTORY_SIZE);
        ViewSessions.registerOnline(targetPlayer.getUUID(), gui);
        gui.open();
    }

    private static void redirectGrid(SimpleGui gui, Container inv, int size) {
        for (int i = 0; i < size; i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlot(i, new Slot(inv, i, x, y));
        }
    }

    private static void fillBarrier(SimpleGui gui, int from) {
        var barrier = new GuiElementBuilder(Items.BARRIER).setName(Component.literal("§cNot available")).build();
        for (int i = from; i < gui.getVirtualSize(); i++) {
            gui.setSlot(i, barrier);
        }
    }

    /** An online target, or a detached "ghost" entity holding an offline target's saved data. */
    private record Resolved(ServerPlayer player, ViewSessions.OfflineSession offlineSession) {
    }

    /**
     * Resolves the command's target, online or offline, applying every gate:
     * the real-player rule (either path) and the offline rule + two-viewer
     * guard (offline only). One place so both subcommands enforce the same
     * checks - see PLAN.md's rule matrix.
     */
    private static Resolved resolveTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(context, "player");

        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            boolean isBot = online instanceof EntityPlayerMPFake;
            if (!isBot && !isViewAllowed(source, BotInventoryRules.viewRealPlayerInventory)) {
                throw NOT_ALLOWED_REAL_PLAYER.create();
            }
            return new Resolved(online, null);
        }

        if (!isViewAllowed(source, BotInventoryRules.viewOfflinePlayerInventory)) {
            throw OFFLINE_VIEWING_DISABLED.create();
        }

        Optional<OfflineInventoryAccess.Target> offline = OfflineInventoryAccess.resolve(server, name);
        if (offline.isEmpty()) {
            throw NO_SAVED_DATA.create();
        }

        OfflineInventoryAccess.Target target = offline.get();
        if (!target.isBot() && !isViewAllowed(source, BotInventoryRules.viewRealPlayerInventory)) {
            throw NOT_ALLOWED_REAL_PLAYER.create();
        }

        ViewSessions.OfflineSession session = new ViewSessions.OfflineSession(target.entry(), target.data());
        if (!ViewSessions.tryRegisterOffline(target.entry().id(), session)) {
            throw ALREADY_OPEN.create();
        }

        ServerPlayer ghost;
        try {
            ghost = OfflineInventoryAccess.createGhost(server, target.entry(), target.data());
        } catch (RuntimeException e) {
            ViewSessions.unregisterOffline(target.entry().id(), session);
            throw e;
        }
        return new Resolved(ghost, session);
    }

    private static int viewInventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException();
        Resolved target = resolveTarget(context);

        if (target.offlineSession() == null) {
            openInventory(viewer, target.player());
        } else {
            openOfflineInventory(context.getSource().getServer(), viewer, target.player(), target.offlineSession());
        }
        return 1;
    }

    private static void openOfflineInventory(MinecraftServer server, ServerPlayer viewer, ServerPlayer ghost, ViewSessions.OfflineSession session) {
        OfflineViewGui gui = new OfflineViewGui(MenuType.GENERIC_9x5, viewer, server, session, ghost);
        gui.setTitle(ghost.getName());
        session.gui = gui;
        redirectGrid(gui, ghost.getInventory(), DISPLAYED_INVENTORY_SIZE);
        fillBarrier(gui, DISPLAYED_INVENTORY_SIZE);
        gui.open();
    }

    private static int viewEnderchest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer viewer = context.getSource().getPlayerOrException();
        Resolved target = resolveTarget(context);
        MinecraftServer server = context.getSource().getServer();

        PlayerEnderChestContainer enderChest = target.player().getEnderChestInventory();
        MenuType<?> menuType = switch (enderChest.getContainerSize()) {
            case 9 -> MenuType.GENERIC_9x1;
            case 18 -> MenuType.GENERIC_9x2;
            case 27 -> MenuType.GENERIC_9x3;
            case 36 -> MenuType.GENERIC_9x4;
            case 45 -> MenuType.GENERIC_9x5;
            case 54 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };

        if (target.offlineSession() == null) {
            openEnderchestOnline(viewer, target.player(), enderChest, menuType);
        } else {
            openEnderchestOffline(server, viewer, target.player(), enderChest, menuType, target.offlineSession());
        }
        return 1;
    }

    private static void openEnderchestOnline(ServerPlayer viewer, ServerPlayer targetPlayer, PlayerEnderChestContainer enderChest, MenuType<?> type) {
        OnlineViewGui gui = new OnlineViewGui(type, viewer, targetPlayer.getUUID());
        gui.setTitle(targetPlayer.getName());
        redirectGrid(gui, enderChest, enderChest.getContainerSize());
        ViewSessions.registerOnline(targetPlayer.getUUID(), gui);
        gui.open();
    }

    private static void openEnderchestOffline(MinecraftServer server, ServerPlayer viewer, ServerPlayer ghost, PlayerEnderChestContainer enderChest, MenuType<?> type, ViewSessions.OfflineSession session) {
        OfflineViewGui gui = new OfflineViewGui(type, viewer, server, session, ghost);
        gui.setTitle(ghost.getName());
        session.gui = gui;
        redirectGrid(gui, enderChest, enderChest.getContainerSize());
        gui.open();
    }

    /** Online GUI: closed by ViewSessions when the target logs out, so it never edits an orphaned inventory (see PLAN.md step 0). */
    private static final class OnlineViewGui extends SimpleGui {
        private final java.util.UUID target;

        OnlineViewGui(MenuType<?> type, ServerPlayer viewer, java.util.UUID target) {
            super(type, viewer, false);
            this.target = target;
        }

        @Override
        public void onManualClose() {
            super.onManualClose();
            ViewSessions.unregisterOnline(target, this);
        }
    }

    /**
     * Offline GUI: writes back to disk whenever the ghost's inventory
     * actually changes, checked every tick rather than on a per-slot mutation
     * hook. Slot#markDirty() is not a reliable "something changed" signal -
     * see the comment on OfflineInventoryAccess#currentEditedSnapshot for why
     * a per-slot hook missed single-item drops and caused a duplication bug.
     */
    private static final class OfflineViewGui extends SimpleGui {
        private final MinecraftServer server;
        private final ViewSessions.OfflineSession session;
        private final ServerPlayer ghost;
        private CompoundTag lastWritten;

        OfflineViewGui(MenuType<?> type, ServerPlayer viewer, MinecraftServer server, ViewSessions.OfflineSession session, ServerPlayer ghost) {
            super(type, viewer, false);
            this.server = server;
            this.session = session;
            this.ghost = ghost;
            this.lastWritten = OfflineInventoryAccess.currentEditedSnapshot(server, ghost);
        }

        @Override
        public void onTick() {
            super.onTick();
            syncIfChanged();
        }

        @Override
        public void onManualClose() {
            super.onManualClose();
            syncIfChanged();
            ViewSessions.unregisterOffline(session.entry.id(), session);
        }

        private void syncIfChanged() {
            if (session.stale) return;

            CompoundTag current = OfflineInventoryAccess.currentEditedSnapshot(server, ghost);
            if (current.equals(lastWritten)) return;

            if (OfflineInventoryAccess.writeBack(server, session, ghost)) {
                lastWritten = current;
            } else {
                getPlayer().sendSystemMessage(Component.literal(
                    "Your last changes to " + session.entry.name() + "'s inventory were not saved."
                ));
            }
        }
    }
}

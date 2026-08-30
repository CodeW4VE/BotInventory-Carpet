package froyln.botinventory;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.util.Optional;

import static net.minecraft.server.command.CommandManager.literal;

public class ViewCommand {
    private static final SimpleCommandExceptionType PLAYER_NOT_FOUND =
        new SimpleCommandExceptionType(Text.literal("Player not found or not online"));
    private static final SimpleCommandExceptionType NOT_ALLOWED_REAL_PLAYER =
        new SimpleCommandExceptionType(Text.literal("Not allowed to view a real player's inventory"));
    private static final SimpleCommandExceptionType ALREADY_OPEN =
        new SimpleCommandExceptionType(Text.literal("Someone else is already viewing this player's saved data"));

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
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

    public static boolean isPlayerAllowed(ServerPlayerEntity player, String ruleValue) {
        return switch (ruleValue) {
            case "true" -> true;
            case "false" -> false;
            case "ops" -> hasPermission(player, 2);
            default -> {
                try {
                    yield hasPermission(player, Integer.parseInt(ruleValue));
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    private static boolean hasPermission(ServerPlayerEntity player, int level) {
        return player.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(level)));
    }

    private static boolean hasPermission(ServerCommandSource source, int level) {
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(level)));
    }

    /** Opens the online, live-redirect inventory GUI. Also used directly by the right-click mixin (bot-only, always online). */
    public static void openInventory(ServerPlayerEntity player, ServerPlayerEntity targetPlayer) {
        OnlineViewGui gui = new OnlineViewGui(ScreenHandlerType.GENERIC_9X5, player, targetPlayer.getUuid());
        gui.setTitle(targetPlayer.getName());

        var targetInv = targetPlayer.getInventory();
        for (int i = 0; i < targetInv.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new Slot(targetInv, i, x, y));
        }

        // ponytail: 9x5 has 45 slots, player inventory only has 36. fill the rest with barrier
        var barrier = new GuiElementBuilder(Items.BARRIER).setName(Text.literal("§cNot available")).build();
        for (int i = targetInv.size(); i < gui.getVirtualSize(); i++) {
            gui.setSlot(i, barrier);
        }

        ViewSessions.registerOnline(targetPlayer.getUuid(), gui);
        gui.open();
    }

    private static boolean isViewAllowed(ServerCommandSource source, String ruleValue) {
        return switch (ruleValue) {
            case "true" -> true;
            case "false" -> false;
            case "ops" -> hasPermission(source, 2);
            default -> {
                try {
                    yield hasPermission(source, Integer.parseInt(ruleValue));
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    /** An online target, or a detached "ghost" entity holding an offline target's saved data. */
    private record Resolved(ServerPlayerEntity player, ViewSessions.OfflineSession offlineSession) {
    }

    /**
     * Resolves the command's target, online or offline, applying every gate:
     * the real-player rule (either path) and the offline rule + two-viewer
     * guard (offline only). One place so both subcommands enforce the same
     * checks - see PLAN.md's rule matrix.
     */
    private static Resolved resolveTarget(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(context, "player");

        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        if (online != null) {
            boolean isBot = online instanceof EntityPlayerMPFake;
            if (!isBot && !isViewAllowed(source, BotInventoryRules.viewRealPlayerInventory)) {
                throw NOT_ALLOWED_REAL_PLAYER.create();
            }
            return new Resolved(online, null);
        }

        if (!isViewAllowed(source, BotInventoryRules.viewOfflinePlayerInventory)) {
            throw PLAYER_NOT_FOUND.create();
        }

        Optional<OfflineInventoryAccess.Target> offline = OfflineInventoryAccess.resolve(server, name);
        if (offline.isEmpty()) {
            throw PLAYER_NOT_FOUND.create();
        }

        OfflineInventoryAccess.Target target = offline.get();
        if (!target.isBot() && !isViewAllowed(source, BotInventoryRules.viewRealPlayerInventory)) {
            throw NOT_ALLOWED_REAL_PLAYER.create();
        }

        ViewSessions.OfflineSession session = new ViewSessions.OfflineSession(target.entry(), target.data());
        if (!ViewSessions.tryRegisterOffline(target.entry().id(), session)) {
            throw ALREADY_OPEN.create();
        }

        ServerPlayerEntity ghost;
        try {
            ghost = OfflineInventoryAccess.createGhost(server, target.entry(), target.data());
        } catch (RuntimeException e) {
            ViewSessions.unregisterOffline(target.entry().id(), session);
            throw e;
        }
        return new Resolved(ghost, session);
    }

    private static int viewInventory(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity viewer = context.getSource().getPlayerOrThrow();
        Resolved target = resolveTarget(context);

        if (target.offlineSession() == null) {
            openInventory(viewer, target.player());
        } else {
            openOfflineInventory(context.getSource().getServer(), viewer, target.player(), target.offlineSession());
        }
        return 1;
    }

    private static void openOfflineInventory(MinecraftServer server, ServerPlayerEntity viewer, ServerPlayerEntity ghost, ViewSessions.OfflineSession session) {
        OfflineViewGui gui = new OfflineViewGui(ScreenHandlerType.GENERIC_9X5, viewer, server, session, ghost);
        gui.setTitle(ghost.getName());
        session.gui = gui;

        var ghostInv = ghost.getInventory();
        for (int i = 0; i < ghostInv.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new WriteBackSlot(ghostInv, i, x, y, () -> OfflineInventoryAccess.writeBack(server, session, ghost)));
        }

        var barrier = new GuiElementBuilder(Items.BARRIER).setName(Text.literal("§cNot available")).build();
        for (int i = ghostInv.size(); i < gui.getVirtualSize(); i++) {
            gui.setSlot(i, barrier);
        }

        gui.open();
    }

    private static int viewEnderchest(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity viewer = context.getSource().getPlayerOrThrow();
        Resolved target = resolveTarget(context);
        MinecraftServer server = context.getSource().getServer();

        EnderChestInventory enderChest = target.player().getEnderChestInventory();
        ScreenHandlerType<?> screenHandlerType = switch (enderChest.size()) {
            case 9 -> ScreenHandlerType.GENERIC_9X1;
            case 18 -> ScreenHandlerType.GENERIC_9X2;
            case 27 -> ScreenHandlerType.GENERIC_9X3;
            case 36 -> ScreenHandlerType.GENERIC_9X4;
            case 45 -> ScreenHandlerType.GENERIC_9X5;
            case 54 -> ScreenHandlerType.GENERIC_9X6;
            default -> ScreenHandlerType.GENERIC_9X3;
        };

        if (target.offlineSession() == null) {
            openEnderchestOnline(viewer, target.player(), enderChest, screenHandlerType);
        } else {
            openEnderchestOffline(server, viewer, target.player(), enderChest, screenHandlerType, target.offlineSession());
        }
        return 1;
    }

    private static void openEnderchestOnline(ServerPlayerEntity viewer, ServerPlayerEntity targetPlayer, EnderChestInventory enderChest, ScreenHandlerType<?> type) {
        OnlineViewGui gui = new OnlineViewGui(type, viewer, targetPlayer.getUuid());
        gui.setTitle(targetPlayer.getName());

        for (int i = 0; i < enderChest.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new Slot(enderChest, i, x, y));
        }

        ViewSessions.registerOnline(targetPlayer.getUuid(), gui);
        gui.open();
    }

    private static void openEnderchestOffline(MinecraftServer server, ServerPlayerEntity viewer, ServerPlayerEntity ghost, EnderChestInventory enderChest, ScreenHandlerType<?> type, ViewSessions.OfflineSession session) {
        OfflineViewGui gui = new OfflineViewGui(type, viewer, server, session, ghost);
        gui.setTitle(ghost.getName());
        session.gui = gui;

        for (int i = 0; i < enderChest.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new WriteBackSlot(enderChest, i, x, y, () -> OfflineInventoryAccess.writeBack(server, session, ghost)));
        }

        gui.open();
    }

    /** Online GUI: closed by ViewSessions when the target logs out, so it never edits an orphaned inventory (see PLAN.md step 0). */
    private static final class OnlineViewGui extends SimpleGui {
        private final java.util.UUID target;

        OnlineViewGui(ScreenHandlerType<?> type, ServerPlayerEntity viewer, java.util.UUID target) {
            super(type, viewer, false);
            this.target = target;
        }

        @Override
        public void onClose() {
            super.onClose();
            ViewSessions.unregisterOnline(target, this);
        }
    }

    /** Offline GUI: writes back to disk on close (unless the session went stale - the target logged in mid-edit). */
    private static final class OfflineViewGui extends SimpleGui {
        private final MinecraftServer server;
        private final ViewSessions.OfflineSession session;
        private final ServerPlayerEntity ghost;

        OfflineViewGui(ScreenHandlerType<?> type, ServerPlayerEntity viewer, MinecraftServer server, ViewSessions.OfflineSession session, ServerPlayerEntity ghost) {
            super(type, viewer, false);
            this.server = server;
            this.session = session;
            this.ghost = ghost;
        }

        @Override
        public void onClose() {
            super.onClose();
            if (!session.stale && !OfflineInventoryAccess.writeBack(server, session, ghost)) {
                getPlayer().sendMessage(Text.literal(
                    "Your last changes to " + session.entry.name() + "'s inventory were not saved."
                ));
            }
            ViewSessions.unregisterOffline(session.entry.id(), session);
        }
    }

    /** Writes back to disk on every slot mutation, not only on close, to shrink the race window (see PLAN.md). */
    private static final class WriteBackSlot extends Slot {
        private final Runnable onChange;

        WriteBackSlot(Inventory inventory, int index, int x, int y, Runnable onChange) {
            super(inventory, index, x, y);
            this.onChange = onChange;
        }

        @Override
        public void markDirty() {
            super.markDirty();
            onChange.run();
        }
    }
}

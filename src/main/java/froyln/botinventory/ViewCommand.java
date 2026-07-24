package froyln.botinventory;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class ViewCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var playerNode = dispatcher.getRoot().getChild("player");
        if (playerNode == null) return;

        var viewNode = literal("view")
            .then(literal("inventory")
                .requires(source -> isViewAllowed(source, BotInventoryRules.viewPlayerInventoryCommand))
                .executes(ViewCommand::viewInventory))
            .then(literal("enderchest")
                .requires(source -> isViewAllowed(source, BotInventoryRules.viewPlayerEnderchestCommand))
                .executes(ViewCommand::viewEnderchest))
            .build();

        playerNode.addChild(viewNode);
    }

    private static boolean isViewAllowed(CommandSourceStack source, String ruleValue) {
        return switch (ruleValue) {
            case "true" -> true;
            case "false" -> false;
            case "op" -> source.hasPermission(2);
            default -> {
                try {
                    yield source.hasPermission(Integer.parseInt(ruleValue));
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    private static ServerPlayer getTargetPlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        return server.getPlayerList().getPlayerByName(playerName);
    }

    private static int viewInventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer targetPlayer = getTargetPlayer(context);

        if (targetPlayer == null) {
            context.getSource().sendFailure(Component.literal("Player not found or not online"));
            return 0;
        }

        SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x5, player, false);
        gui.setTitle(targetPlayer.getName());

        for (int i = 0; i < gui.getSize(); i++) {
            gui.setSlot(i, new GuiElementBuilder(Items.BARRIER).setName(Component.literal("")).build());
        }

        for (int i = 0; i < targetPlayer.getInventory().getContainerSize(); i++) {
            gui.setSlot(i, new Slot(targetPlayer.getInventory(), i, 0, 0));
        }

        gui.open();
        return 1;
    }

    private static int viewEnderchest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer targetPlayer = getTargetPlayer(context);

        if (targetPlayer == null) {
            context.getSource().sendFailure(Component.literal("Player not found or not online"));
            return 0;
        }

        PlayerEnderChestContainer enderChest = targetPlayer.getEnderChestInventory();

        MenuType<?> menuType = switch (enderChest.getContainerSize()) {
            case 9 -> MenuType.GENERIC_9x1;
            case 18 -> MenuType.GENERIC_9x2;
            case 27 -> MenuType.GENERIC_9x3;
            case 36 -> MenuType.GENERIC_9x4;
            case 45 -> MenuType.GENERIC_9x5;
            case 54 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };

        SimpleGui gui = new SimpleGui(menuType, player, false);
        gui.setTitle(targetPlayer.getName());

        for (int i = 0; i < gui.getSize(); i++) {
            gui.setSlot(i, new GuiElementBuilder(Items.BARRIER).setName(Component.literal("")).build());
        }

        for (int i = 0; i < enderChest.getContainerSize(); i++) {
            gui.setSlot(i, new Slot(enderChest, i, 0, 0));
        }

        gui.open();
        return 1;
    }
}

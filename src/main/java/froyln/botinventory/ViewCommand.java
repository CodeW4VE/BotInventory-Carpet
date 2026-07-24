package froyln.botinventory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import static net.minecraft.server.command.CommandManager.literal;

public class ViewCommand {
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

    private static boolean isViewAllowed(ServerCommandSource source, String ruleValue) {
        return switch (ruleValue) {
            case "true" -> true;
            case "false" -> false;
            case "op" -> source.hasPermissionLevel(2);
            default -> {
                try {
                    yield source.hasPermissionLevel(Integer.parseInt(ruleValue));
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
        };
    }

    private static ServerPlayerEntity getTargetPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String playerName = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        return server.getPlayerManager().getPlayer(playerName);
    }

    private static int viewInventory(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity targetPlayer = getTargetPlayer(context);

        if (targetPlayer == null) {
            context.getSource().sendError(Text.literal("Player not found or not online"));
            return 0;
        }

        SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X5, player, false);
        gui.setTitle(targetPlayer.getName());

        var targetInv = targetPlayer.getInventory();
        for (int i = 0; i < targetInv.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new Slot(targetInv, i, x, y));
        }

        gui.open();
        return 1;
    }

    private static int viewEnderchest(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity targetPlayer = getTargetPlayer(context);

        if (targetPlayer == null) {
            context.getSource().sendError(Text.literal("Player not found or not online"));
            return 0;
        }

        EnderChestInventory enderChest = targetPlayer.getEnderChestInventory();

        ScreenHandlerType<?> screenHandlerType = switch (enderChest.size()) {
            case 9 -> ScreenHandlerType.GENERIC_9X1;
            case 18 -> ScreenHandlerType.GENERIC_9X2;
            case 27 -> ScreenHandlerType.GENERIC_9X3;
            case 36 -> ScreenHandlerType.GENERIC_9X4;
            case 45 -> ScreenHandlerType.GENERIC_9X5;
            case 54 -> ScreenHandlerType.GENERIC_9X6;
            default -> ScreenHandlerType.GENERIC_9X3;
        };

        SimpleGui gui = new SimpleGui(screenHandlerType, player, false);
        gui.setTitle(targetPlayer.getName());

        for (int i = 0; i < enderChest.size(); i++) {
            int x = 8 + (i % 9) * 18;
            int y = 18 + (i / 9) * 18;
            gui.setSlotRedirect(i, new Slot(enderChest, i, x, y));
        }

        gui.open();
        return 1;
    }
}

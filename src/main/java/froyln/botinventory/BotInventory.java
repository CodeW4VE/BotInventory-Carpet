package froyln.botinventory;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

public class BotInventory implements CarpetExtension {
    public static final String MOD_ID = "botinventory-carpet";

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(BotInventoryRules.class);
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext) {
        ViewCommand.register(dispatcher);
    }

    @Override
    public void onTick(MinecraftServer server) {
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
    }
}

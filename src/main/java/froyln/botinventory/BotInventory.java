package froyln.botinventory;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;

public class BotInventory implements CarpetExtension {
    public static final String MOD_ID = "botinventory-carpet";

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(BotInventoryRules.class);
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess commandBuildContext) {
        ViewCommand.register(dispatcher);
    }

    @Override
    public void onTick(MinecraftServer server) {
    }

    @Override
    public void onServerClosed(MinecraftServer server) {
    }
}

package froyln.botinventory;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

public class BotInventory implements CarpetExtension, ModInitializer {

    static {
        CarpetServer.manageExtension(new BotInventory());
    }

    @Override
    public void onInitialize() {
    }

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(BotInventoryRules.class);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return Map.of(
            "carpet.rule.viewFakePlayerInventoryRightClick.desc", "Allow right click in fake player and see their inventories",
            "carpet.rule.viewPlayerInventoryCommand.desc", "Allow /player view inventory command",
            "carpet.rule.viewPlayerEnderchestCommand.desc", "Allow /player view enderchest command"
        );
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayerEntity player) {
        ViewSessions.onPlayerLoggedOut(player);
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

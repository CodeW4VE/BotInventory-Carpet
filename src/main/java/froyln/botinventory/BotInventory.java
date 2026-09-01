package froyln.botinventory;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
            "carpet.rule.viewPlayerEnderchestCommand.desc", "Allow /player view enderchest command",
            "carpet.rule.viewOfflinePlayerInventory.desc", "Allow /player view inventory|enderchest to target offline players, reading and writing their saved data",
            "carpet.rule.viewRealPlayerInventory.desc", "Allow /player view inventory|enderchest to target real players, not just fake/bot players"
        );
    }

    @Override
    public void onPlayerLoggedIn(ServerPlayer player) {
        ViewSessions.onPlayerLoggedIn(player);
    }

    @Override
    public void onPlayerLoggedOut(ServerPlayer player) {
        ViewSessions.onPlayerLoggedOut(player);
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

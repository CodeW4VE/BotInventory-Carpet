package froyln.botinventory;

import carpet.api.extension.CarpetExtension;
import carpet.api.extension.CarpetExtensionServerState;
import net.minecraft.server.MinecraftServer;

public class BotInventory implements CarpetExtension {
    public static final String MOD_ID = "botinventory-carpet";

    @Override
    public void onServerLoaded(MinecraftServer server) {
        CarpetExtensionServerState.registerExtensionForServer(server, this);
    }

    @Override
    public void tick(MinecraftServer server) {
    }

    @Override
    public void onClosing(MinecraftServer server) {
    }
}

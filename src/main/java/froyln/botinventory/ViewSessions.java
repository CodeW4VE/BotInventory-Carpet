package froyln.botinventory;

import eu.pb4.sgui.api.gui.GuiInterface;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks open inventory-view GUIs so a target logging out can close whichever
 * GUIs are still redirecting into their now-orphaned inventory, instead of
 * leaving one open on it. The server is single-threaded, so a plain
 * collection is safe here.
 */
public final class ViewSessions {
    private ViewSessions() {
    }

    private static final Map<UUID, Set<GuiInterface>> onlineSessions = new HashMap<>();

    public static void registerOnline(UUID target, GuiInterface gui) {
        onlineSessions.computeIfAbsent(target, k -> new HashSet<>()).add(gui);
    }

    public static void unregisterOnline(UUID target, GuiInterface gui) {
        Set<GuiInterface> guis = onlineSessions.get(target);
        if (guis == null) return;
        guis.remove(gui);
        if (guis.isEmpty()) onlineSessions.remove(target);
    }

    /** Target logged out: any GUI still redirecting into their now-orphaned inventory must close (dupe fix). */
    public static void onPlayerLoggedOut(ServerPlayerEntity player) {
        Set<GuiInterface> guis = onlineSessions.remove(player.getUuid());
        if (guis == null) return;
        for (GuiInterface gui : new HashSet<>(guis)) {
            gui.close();
        }
    }
}

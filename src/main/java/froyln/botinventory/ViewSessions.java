package froyln.botinventory;

import eu.pb4.sgui.api.gui.GuiInterface;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks open inventory-view GUIs so a target logging in or out can close
 * whichever GUIs are watching them, instead of leaving one pointed at
 * orphaned or stale data. The server is single-threaded, so plain
 * collections are safe here — a slot write and a login can never interleave.
 */
public final class ViewSessions {
    private ViewSessions() {
    }

    private static final Map<UUID, Set<GuiInterface>> onlineSessions = new HashMap<>();
    private static final Map<UUID, OfflineSession> offlineSessions = new HashMap<>();

    public static final class OfflineSession {
        public final PlayerConfigEntry entry;
        public final NbtCompound openedSnapshot;
        public GuiInterface gui;
        public boolean stale;

        OfflineSession(PlayerConfigEntry entry, NbtCompound openedSnapshot) {
            this.entry = entry;
            this.openedSnapshot = openedSnapshot;
        }
    }

    // --- online sessions: step 0 fix, close GUIs orphaned by target logout ---

    public static void registerOnline(UUID target, GuiInterface gui) {
        onlineSessions.computeIfAbsent(target, k -> new HashSet<>()).add(gui);
    }

    public static void unregisterOnline(UUID target, GuiInterface gui) {
        Set<GuiInterface> guis = onlineSessions.get(target);
        if (guis == null) return;
        guis.remove(gui);
        if (guis.isEmpty()) onlineSessions.remove(target);
    }

    // --- offline sessions ---

    /** Registers the target as having an open offline GUI. False if one is already open (second viewer refused). */
    public static boolean tryRegisterOffline(UUID target, OfflineSession session) {
        return offlineSessions.putIfAbsent(target, session) == null;
    }

    public static void unregisterOffline(UUID target, OfflineSession session) {
        offlineSessions.remove(target, session);
    }

    // --- Carpet hooks, wired from BotInventory ---

    /** Target logged in: any open offline GUI on them is now stale (see PLAN.md race notes). */
    public static void onPlayerLoggedIn(ServerPlayerEntity player) {
        OfflineSession session = offlineSessions.get(player.getUuid());
        if (session == null) return;

        session.stale = true;
        if (session.gui != null) {
            session.gui.getPlayer().sendMessage(Text.literal(
                player.getGameProfile().name() + " just logged in - your changes were not saved. "
                    + "Reopen the view to edit them online."
            ));
            session.gui.close();
        }
    }

    /** Target logged out: any online GUI still redirecting into their now-orphaned inventory must close (step 0 dupe fix). */
    public static void onPlayerLoggedOut(ServerPlayerEntity player) {
        Set<GuiInterface> guis = onlineSessions.remove(player.getUuid());
        if (guis == null) return;
        for (GuiInterface gui : new HashSet<>(guis)) {
            gui.close();
        }
    }
}

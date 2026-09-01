package froyln.botinventory;

import eu.pb4.sgui.api.gui.GuiLike;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.network.chat.Component;

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

    private static final Map<UUID, Set<GuiLike>> onlineSessions = new HashMap<>();
    private static final Map<UUID, OfflineSession> offlineSessions = new HashMap<>();

    public static final class OfflineSession {
        public final NameAndId entry;
        /** What we last knew to be on disk - our own baseline, not "state at open". Advances after every successful write; see PLAN.md. */
        public CompoundTag lastKnownDiskState;
        public GuiLike gui;
        public boolean stale;

        OfflineSession(NameAndId entry, CompoundTag lastKnownDiskState) {
            this.entry = entry;
            this.lastKnownDiskState = lastKnownDiskState;
        }
    }

    // step 0 fix: close GUIs orphaned by target logout
    public static void registerOnline(UUID target, GuiLike gui) {
        onlineSessions.computeIfAbsent(target, k -> new HashSet<>()).add(gui);
    }

    public static void unregisterOnline(UUID target, GuiLike gui) {
        Set<GuiLike> guis = onlineSessions.get(target);
        if (guis == null) return;
        guis.remove(gui);
        if (guis.isEmpty()) onlineSessions.remove(target);
    }

    // offline sessions
    /** Registers the target as having an open offline GUI. False if one is already open (second viewer refused). */
    public static boolean tryRegisterOffline(UUID target, OfflineSession session) {
        return offlineSessions.putIfAbsent(target, session) == null;
    }

    public static void unregisterOffline(UUID target, OfflineSession session) {
        offlineSessions.remove(target, session);
    }

    // Carpet hooks, wired from BotInventory
    /** Target logged in: any open offline GUI on them is now stale (see PLAN.md race notes). */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        OfflineSession session = offlineSessions.get(player.getUUID());
        if (session == null) return;

        session.stale = true;
        if (session.gui != null) {
            session.gui.getPlayer().sendSystemMessage(Component.literal(
                player.getGameProfile().name() + " just logged in - your changes were not saved. "
                    + "Reopen the view to edit them online."
            ));
            session.gui.close();
        }
    }

    /** Target logged out: any online GUI still redirecting into their now-orphaned inventory must close (step 0 dupe fix). */
    public static void onPlayerLoggedOut(ServerPlayer player) {
        Set<GuiLike> guis = onlineSessions.remove(player.getUUID());
        if (guis == null) return;
        for (GuiLike gui : new HashSet<>(guis)) {
            gui.close();
        }
    }
}

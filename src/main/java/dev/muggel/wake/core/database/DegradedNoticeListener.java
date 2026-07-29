package dev.muggel.wake.core.database;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NonNull;

/** Tells joining player that the database is unreachable */
final class DegradedNoticeListener implements Listener {
    private final DatabaseManager databaseManager;
    DegradedNoticeListener(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @EventHandler
    public void onPlayerJoin(@NonNull PlayerJoinEvent event) {
        databaseManager.notifyOnJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(@NonNull PlayerQuitEvent event) {
        databaseManager.forgetActor(event.getPlayer().getUniqueId());
    }
}
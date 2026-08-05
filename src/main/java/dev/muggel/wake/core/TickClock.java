package dev.muggel.wake.core;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;

/**
 * Dates what happened throughout a tick. <br>
 * Every subtick timestamp in Wake comes from here, so events in one tick share a base and stay comparable.
 */
public final class TickClock implements Listener {
    private long start = System.nanoTime();
    private long previous = start;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(@NonNull ServerTickStartEvent event) {
        previous = start;
        start = System.nanoTime();
    }

    public long at(double progress) {
        return previous + (long) (progress * (start - previous));
    }
}
package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Scheduling that tolerates the plugin shutting down. <br>
 * Once Bukkit has disabled the plugin every submission here is silently discarded. <br>
 * It's the wrong tool for teardown work (do that inline)
 */
public final class Scheduling {
    private Scheduling() {}

    public static void onMain(@NonNull Wake plugin, @NonNull Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException disabledMidSubmit) {
            // dropped
        }
    }

    public static void async(@NonNull Wake plugin, @NonNull Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalPluginAccessException disabledMidSubmit) {
            // dropped
        }
    }

    public static @Nullable BukkitTask laterAsync(@NonNull Wake plugin, @NonNull Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return null;
        }
        try {
            return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        } catch (IllegalPluginAccessException disabledMidSubmit) {
            return null;
        }
    }
}
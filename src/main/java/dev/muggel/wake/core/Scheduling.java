package dev.muggel.wake.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Scheduling that tolerates the plugin shutting down. <br>
 * Once Bukkit has disabled the plugin every submission here is silently discarded. <br>
 * It's the wrong tool for teardown work (do that inline)
 */
public final class Scheduling {
    private Scheduling() {}

    public static void onMain(@NonNull Plugin plugin, @NonNull Runnable task) {
        submit(plugin, () -> Bukkit.getScheduler().runTask(plugin, task));
    }

    public static void async(@NonNull Plugin plugin, @NonNull Runnable task) {
        submit(plugin, () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    public static void later(@NonNull Plugin plugin, @NonNull Runnable task, long delayTicks) {
        submit(plugin, () -> Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
    }

    public static @Nullable BukkitTask laterAsync(@NonNull Plugin plugin, @NonNull Runnable task, long delayTicks) {
        return submit(plugin, () -> Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks));
    }

    public static @Nullable BukkitTask repeatingAsync(@NonNull Plugin plugin, @NonNull Runnable task, long delayTicks, long periodTicks) {
        return submit(plugin, () -> Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks));
    }

    private static @Nullable BukkitTask submit(@NonNull Plugin plugin, @NonNull Supplier<BukkitTask> hop) {
        if (!plugin.isEnabled()) {
            return null;
        }
        try {
            return hop.get();
        } catch (IllegalPluginAccessException disabledMidSubmit) {
            return null;
        }
    }
}
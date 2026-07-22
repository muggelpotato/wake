package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDao;
import dev.muggel.wake.features.obu.api.OBUService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class SandboxPurger {
    public static final String STATE_KEY_KEEP_UNUSED = "obu.keep_unused_sandboxes";
    public static final String DEFAULT_KEEP = "30d";
    private static final long MAX_SWEEP_INTERVAL_MILLIS = 3_600_000L;
    private static final long FIRST_SWEEP_DELAY_MILLIS = 60_000L;
    private final Wake plugin;
    private final OBUDao dao;
    private final OBUServiceImpl service;
    private @Nullable BukkitTask task;
    public SandboxPurger(Wake plugin, OBUDao dao, OBUServiceImpl service) {
        this.plugin = plugin;
        this.dao = dao;
        this.service = service;
    }

    public @Nullable BukkitTask start() {
        long keepMillis = configuredKeepMillis();
        if (keepMillis <= 0) return null;
        long intervalTicks = Math.min(keepMillis, MAX_SWEEP_INTERVAL_MILLIS) / 50L;
        long delayTicks = Math.min(FIRST_SWEEP_DELAY_MILLIS / 50L, intervalTicks);
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::sweep, delayTicks, intervalTicks);
        return this.task;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public @Nullable BukkitTask restart() {
        stop();
        return start();
    }

    private long configuredKeepMillis() {
        return parseKeepMillis(plugin.getStateDao().get(STATE_KEY_KEEP_UNUSED, DEFAULT_KEEP));
    }

    private void sweep() {
        long thresholdMillis = configuredKeepMillis();
        if (thresholdMillis <= 0) return;
        long cutoff = System.currentTimeMillis() - thresholdMillis;
        List<String> oldSandboxes = dao.getOldSandboxes(cutoff);
        if (oldSandboxes.isEmpty() || !plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> deleteUnlessActive(oldSandboxes));
        } catch (IllegalPluginAccessException ignored) {
            // skip this run
        }
    }

    private void deleteUnlessActive(List<String> oldSandboxes) {
        if (Wake.getServiceRegistry().get(OBUService.class) != service) return;
        Set<String> activeNow = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String active = service.getPlayerActiveSandbox(p);
            if (active != null) activeNow.add(active.toLowerCase(Locale.ROOT));
            String context = service.getActiveContextName(p);
            if (context != null) activeNow.add(context.toLowerCase(Locale.ROOT));
        }
        for (UUID boatId : service.getSyncManager().getKnownBoatContexts()) {
            if (Bukkit.getEntity(boatId) instanceof Boat boat) {
                String pinned = service.getBoatContextName(boat);
                if (pinned != null) activeNow.add(pinned.toLowerCase(Locale.ROOT));
            }
        }
        for (String oldSandbox : oldSandboxes) {
            if (!activeNow.contains(oldSandbox.toLowerCase(Locale.ROOT))) {
                service.deleteContextAndEvict(oldSandbox);
                plugin.getLogger().info("Purged inactive sandbox: " + oldSandbox);
            }
        }
    }

    public static long parseKeepMillis(@Nullable String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("0") || s.equals("off") || s.equals("never") || s.equals("disabled")) return 0;
        int unitStart = 0;
        while (unitStart < s.length() && Character.isDigit(s.charAt(unitStart))) unitStart++;
        if (unitStart == 0 || unitStart == s.length()) return -1;
        long value;
        try {
            value = Long.parseLong(s.substring(0, unitStart));
        } catch (NumberFormatException e) {
            return -1;
        }
        long unitMillis = switch (s.substring(unitStart)) {
            case "s" -> 1_000L;
            case "min" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 7L * 86_400_000L;
            case "mo" -> 30L * 86_400_000L;
            case "y" -> 365L * 86_400_000L;
            default -> -1L;
        };
        if (unitMillis < 0) return -1;
        if (value == 0) return 0;
        if (unitMillis > Long.MAX_VALUE / value) return -1;
        return value * unitMillis;
    }
}
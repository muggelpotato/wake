package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDao;
import dev.muggel.wake.features.obu.api.OBUService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SandboxPurger {
    public static final String STATE_KEY_KEEP_UNUSED = "obu.keep_unused_sandboxes";
    public static final String DEFAULT_KEEP = "30d";
    private static final long MAX_SWEEP_INTERVAL_MILLIS = 3_600_000L;
    private static final long FIRST_SWEEP_DELAY_MILLIS = 60_000L;
    private final Wake plugin;
    private final OBUDao dao;
    private final OBUServiceImpl service;
    private @Nullable BukkitTask task;
    private long scheduledKeepMillis;
    public SandboxPurger(Wake plugin, OBUDao dao, OBUServiceImpl service) {
        this.plugin = plugin;
        this.dao = dao;
        this.service = service;
    }

    public @Nullable BukkitTask restart() {
        long keepMillis = configuredKeepMillis();
        if (task != null && keepMillis == scheduledKeepMillis) {
            return null;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (keepMillis <= 0) return null;
        long intervalTicks = Math.min(keepMillis, MAX_SWEEP_INTERVAL_MILLIS) / 50L;
        long delayTicks = Math.min(FIRST_SWEEP_DELAY_MILLIS / 50L, intervalTicks);
        scheduledKeepMillis = keepMillis;
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::sweep, delayTicks, intervalTicks);
        return this.task;
    }

    private long configuredKeepMillis() {
        return parseKeepMillis(plugin.getStateDao().get(STATE_KEY_KEEP_UNUSED, DEFAULT_KEEP));
    }

    private void sweep() {
        long thresholdMillis = configuredKeepMillis();
        if (thresholdMillis <= 0) return;
        long cutoff = System.currentTimeMillis() - thresholdMillis;
        List<String> expired = dao.getOldSandboxes(cutoff);
        if (expired.isEmpty()) return;
        Scheduling.onMain(plugin, () -> purge(expired));
    }

    private void purge(@NonNull List<String> expired) {
        if (Wake.getServiceRegistry().get(OBUService.class) != service) return;
        Set<String> gone = new HashSet<>();
        for (String sandbox : expired) {
            gone.add(sandbox.toLowerCase(Locale.ROOT));
            dao.deleteContext(sandbox);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            String lost = lostContext(gone, player);
            if (lost == null) {
                continue;
            }
            service.applyDefaultContext(player);
            plugin.getMessageManager().send(player, "commands.obu.sandbox.purged", Placeholder.unparsed("sandbox", OBUContextManager.displayName(lost)));
        }
        plugin.getLogger().info("Purged " + expired.size() + " sandbox(es) unused past the keep window");
    }

    private @Nullable String lostContext(@NonNull Set<String> gone, @NonNull Player player) {
        String sandbox = service.getPlayerActiveSandbox(player);
        if (sandbox != null && gone.contains(sandbox.toLowerCase(Locale.ROOT))) {
            return sandbox;
        }
        String context = service.getActiveContextName(player);
        return gone.contains(context.toLowerCase(Locale.ROOT)) ? context : null;
    }

    public static long parseKeepMillis(@NonNull String raw) {
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
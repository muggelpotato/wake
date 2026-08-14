package dev.muggel.wake.features.obu.contexts;

import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDao;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class SandboxPurger {
    public static final String STATE_KEY_KEEP_UNUSED = "obu.keep_unused_sandboxes";
    private static final String DEFAULT_KEEP = "30d";
    private static final long MAX_SWEEP_INTERVAL_MILLIS = 3_600_000L;
    private static final long FIRST_SWEEP_DELAY_MILLIS = 60_000L;
    private final Wake plugin;
    private final OBUDao dao;
    private final ContextDelivery service;
    private @Nullable BukkitTask task;
    private @Nullable String scheduled;
    public SandboxPurger(Wake plugin, OBUDao dao, ContextDelivery service) {
        this.plugin = plugin;
        this.dao = dao;
        this.service = service;
    }

    public @Nullable BukkitTask restart() {
        String configured = configuredKeep(plugin);
        if (configured.equals(scheduled)) {
            return null;
        }
        scheduled = configured;
        if (task != null) {
            task.cancel();
            task = null;
        }
        long keepMillis = parseKeepMillis(configured);
        if (keepMillis < 0) {
            plugin.getLogger().warning("Automatic sandbox purging is off: " + STATE_KEY_KEEP_UNUSED + " is '" + configured + "', which is not a duration");
        }
        if (keepMillis <= 0) return null;
        long intervalTicks = Math.min(keepMillis, MAX_SWEEP_INTERVAL_MILLIS) / 50L;
        long delayTicks = Math.min(FIRST_SWEEP_DELAY_MILLIS / 50L, intervalTicks);
        this.task = Scheduling.repeatingAsync(plugin, this::sweep, delayTicks, intervalTicks);
        return this.task;
    }

    public static @NonNull String configuredKeep(@NonNull Wake plugin) {
        return plugin.getStateDao().get(STATE_KEY_KEEP_UNUSED, DEFAULT_KEEP);
    }

    private void sweep() {
        long thresholdMillis = parseKeepMillis(configuredKeep(plugin));
        if (thresholdMillis <= 0) return;
        long cutoff = System.currentTimeMillis() - thresholdMillis;
        plugin.getDatabaseManager().readAsync(() -> dao.getOldSandboxes(cutoff), this::purge);
    }

    private void purge(@Nullable List<String> expired) {
        if (expired == null || expired.isEmpty() || service.isStale()) return;
        service.deleteContextsAndEvict(expired).forEach((player, lost) ->
                plugin.getMessageManager().send(player, "commands.obu.sandbox.purged", Placeholder.unparsed("sandbox", OBUContextManager.displayName(lost))));
        plugin.getLogger().info("Purged " + expired.size() + " sandbox(es) unused past the keep window");
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
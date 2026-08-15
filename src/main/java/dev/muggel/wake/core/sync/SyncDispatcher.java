package dev.muggel.wake.core.sync;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.core.module.WakeModule;
import org.jspecify.annotations.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Turns announcements into reloads. <br>
 * Payloads arrive on a network thread and reloads happen on the main one
 */
class SyncDispatcher {
    private final Wake plugin;
    private final Set<String> pendingScopes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    SyncDispatcher(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    void accept(@NonNull String payload) {
        SyncMessage message = SyncMessage.parse(payload);
        plugin.getDatabaseManager().markRemoteChange(message.scope(), message.table(), message.keys());
        pendingScopes.add(message.scope());
        if (flushScheduled.compareAndSet(false, true)) {
            Scheduling.onMain(plugin, this::flush);
        }
    }

    /** Reloads every scope announced since last tick (multiple same scope announcements collapse into one reload) */
    private void flush() {
        if (plugin.getDatabaseManager().isDegraded()) {
            flushScheduled.set(false);
            return;
        }
        Set<String> scopes = new HashSet<>();
        pendingScopes.removeIf(scopes::add);
        flushScheduled.set(false);
        if (!pendingScopes.isEmpty() && flushScheduled.compareAndSet(false, true)) {
            Scheduling.onMain(plugin, this::flush);
        }
        boolean verbose = plugin.getConfig().getBoolean("sync.verbose_logging", false);
        if (scopes.contains(SyncService.SCOPE_STATE) || scopes.contains(SyncService.SCOPE_FULL)) {
            boolean everything = scopes.contains(SyncService.SCOPE_FULL);
            plugin.getStateDao().reloadAsync(changedKeys -> {
                for (WakeModule module : plugin.getActiveModules()) {
                    if (everything || scopes.contains(module.getId()) || ownsAnyKey(module.getId(), changedKeys)) {
                        reloadQuietly(module, verbose);
                    }
                }
                plugin.seedDeferredModules();
            });
            return;
        }
        for (WakeModule module : plugin.getActiveModules()) {
            if (scopes.contains(module.getId())) {
                reloadQuietly(module, verbose);
            }
        }
    }

    private static boolean ownsAnyKey(@NonNull String moduleId, @NonNull Set<String> keys) {
        String prefix = moduleId + ".";
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void reloadQuietly(WakeModule module, boolean verbose) {
        try {
            module.reload();
            if (verbose) {
                plugin.getLogger().info("Synced module '" + module.getId() + "' from another server");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to sync module '" + module.getId() + "'", e);
        }
    }
}
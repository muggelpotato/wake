package dev.muggel.wake.core.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.sync.SyncService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which stores mirror which tables. <br>
 * In- and outbound invalidations pass through here: <br>
 * 1. in: change is routed to the stores that hold the rows <br>
 * 2. out: change made here is collected until the write behind it has committed and can be announced
 */
class MirrorRegistry {
    private final Wake plugin;
    private final List<CachedStore<?>> mirrors = new CopyOnWriteArrayList<>();
    private final Set<String> dirtyScopes = ConcurrentHashMap.newKeySet();
    private final Map<CachedStore<?>, Set<String>> dirtyKeys = new ConcurrentHashMap<>();
    MirrorRegistry(@NonNull Wake plugin) {
        this.plugin = plugin;
    }

    void register(@NonNull CachedStore<?> mirror) {
        mirrors.add(mirror);
    }

    /** Stops routing invalidations to a mirror whose module is going away */
    void release(@NonNull CachedStore<?> mirror) {
        mirrors.remove(mirror);
    }

    /** Another server changed {@code keys} in {@code scope}, or the whole of it when {@code keys} is {@code null} */
    void markRemoteChange(@NonNull String scope, @Nullable String table, @Nullable Collection<String> keys) {
        boolean everything = SyncService.SCOPE_FULL.equals(scope);
        for (CachedStore<?> mirror : mirrors) {
            if (everything || (mirror.scope().equals(scope) && (table == null || table.equals(mirror.table())))) {
                mirror.markStale(everything ? null : keys);
            }
        }
    }

    /** Remembers what a write just moved, to be announced once the queue reaches a publish */
    void recordLocalChange(@Nullable CachedStore<?> mirror, @NonNull List<String> rowKeys, @Nullable String scope) {
        if (mirror != null && !rowKeys.isEmpty()) {
            dirtyKeys.computeIfAbsent(mirror, ignored -> ConcurrentHashMap.newKeySet()).addAll(rowKeys);
        } else if (scope != null) {
            dirtyScopes.add(scope);
        }
    }

    /** Announces everything collected since the last call. Writer thread only, like the batch it drains */
    void publishPending() {
        if (dirtyScopes.isEmpty() && dirtyKeys.isEmpty()) {
            return;
        }
        List<String> scopes = List.copyOf(dirtyScopes);
        dirtyScopes.clear();
        Map<CachedStore<?>, Set<String>> keyed = Map.copyOf(dirtyKeys);
        dirtyKeys.clear();
        SyncService sync = plugin.getSyncService();
        if (sync == null) {
            return;
        }
        for (String scope : scopes) {
            sync.publish(scope);
        }
        keyed.forEach((mirror, keys) -> {
            if (!scopes.contains(mirror.scope())) {
                sync.publishKeys(mirror.scope(), mirror.table(), keys);
            }
        });
    }
}
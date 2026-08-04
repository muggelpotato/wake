package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.sync.SyncService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Key-value store for runtime flags and settings (cached in memory, persisted asynchronously). <br>
 * Keys are prefixed with the owning module's id ({@code "base.show_hints"}), which is what scopes export, import, and reset per module. <br>
 * Values are stored as JSON (prefer booleans and strings): <br>
 * All JSON numbers come back as {@code Double} after a restart (the getter coerces scalars to the default's type, but list elements get no coercion).
 */
public class StateDao extends WakeDao {
    private static final String UPSERT = "REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)";
    private static final String DELETE_PREFIX = "DELETE FROM wake_state WHERE state_key LIKE ? ESCAPE '!'";
    private final Gson gson = new Gson();
    private final CachedStore<Object> state = mirror("wake_state", this::readState);
    public StateDao(@NonNull Wake plugin) {
        super(plugin);
        initTables();
        state.load();
    }

    @Override
    protected String syncScope() {
        return SyncService.SCOPE_STATE;
    }

    @Override
    protected Map<String, String> getTableSchemas() {
        return Map.of("wake_state", """
                CREATE TABLE IF NOT EXISTS wake_state (
                    state_key VARCHAR(255) PRIMARY KEY,
                    state_value TEXT
                )
                """);
    }

    public boolean isLoaded() {
        return state.isLoaded();
    }

    /**
     * Reloads the cache without blocking the main thread. <br>
     * Cross-server sync uses the hook to reload modules only after they can observe the fresh state values.
     */
    public void reloadAsync(@Nullable Consumer<Set<String>> afterApply) {
        state.reloadAsync(afterApply);
    }

    private @NonNull Map<String, Object> readState(@Nullable Set<String> keys) throws SQLException {
        Map<String, Object> loaded = new HashMap<>();
        selectByKeys("SELECT state_key, state_value FROM wake_state", "state_key", keys, row -> {
            String key = row.getString("state_key");
            try {
                Object value = gson.fromJson(row.getString("state_value"), Object.class);
                if (value != null) {
                    loaded.put(key, value);
                }
            } catch (JsonSyntaxException e) {
                plugin.getLogger().warning("Skipping malformed state row '" + key + "': " + e.getMessage());
            }
        });
        return loaded;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = state.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (defaultValue instanceof Integer && val instanceof Number n) {
            return (T) Integer.valueOf(n.intValue());
        }
        if (defaultValue instanceof Long && val instanceof Number n) {
            return (T) Long.valueOf(n.longValue());
        }
        if (defaultValue instanceof Double && val instanceof Number n) {
            return (T) Double.valueOf(n.doubleValue());
        }
        if (defaultValue != null && !defaultValue.getClass().isInstance(val)) {
            return defaultValue;
        }
        return (T) val;
    }

    public void set(String key, Object value) {
        state.save(key, value, "Failed to save state", List.of(new SqlStatement(UPSERT, new Object[]{key, gson.toJson(value)})));
    }

    public boolean toggle(String key, boolean defaultValue) {
        boolean newState = !get(key, defaultValue);
        set(key, newState);
        return newState;
    }

    public boolean has(String key) {
        return state.containsKey(key);
    }

    public void importValue(String key, Object value) throws SQLException {
        DB.executeUpdate(UPSERT, key, gson.toJson(value));
        state.announce(key, value);
    }

    public Map<String, Object> snapshot(String prefix) {
        Map<String, Object> out = new HashMap<>();
        state.view().forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k, v);
            }
        });
        return out;
    }

    public void clearPrefix(String prefix) {
        for (String key : List.copyOf(state.keys())) {
            if (key.startsWith(prefix)) {
                state.forget(key);
            }
        }
        String pattern = prefix.replaceAll("([!%_])", "!$1") + "%";
        state.announceWholeScope("Failed to clear state", List.of(new SqlStatement(DELETE_PREFIX, new Object[]{pattern})));
    }
}
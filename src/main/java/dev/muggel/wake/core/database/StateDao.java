package dev.muggel.wake.core.database;

import co.aikar.idb.DB;
import com.google.gson.Gson;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.sync.SyncService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Key-value store for runtime flags and settings (cached in memory, persisted asynchronously). <br>
 * Keys are prefixed with the owning module's id ({@code "base.show_hints"}), which is what scopes export, import, and reset per module. <br>
 * Values are stored as JSON (prefer booleans and strings): <br>
 * All JSON numbers come back as {@code Double} after a restart (the getter coerces scalars to the default's type, but list elements get no coercion).
 */
public class StateDao extends WakeDao {
    private final Gson gson = new Gson();
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    public StateDao(@NonNull Wake plugin) {
        super(plugin);
        initTables();
        load();
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

    /** Synchronous load (boot-time only). Reload paths use {@link #reloadAsync}. */
    public void load() {
        Map<String, Object> fresh = fetchAll();
        state.clear();
        state.putAll(fresh);
    }

    /**
     * Reloads the cache without blocking the main thread (read async, swap on the main thread).
     * Cross-server sync uses the hook to reload modules only after they can observe the fresh state values.
     */
    public void reloadAsync(@Nullable Runnable afterApply) {
        plugin.getDatabaseManager().readAsync(this::fetchAll, fresh -> {
            state.clear();
            state.putAll(fresh);
            if (afterApply != null) {
                afterApply.run();
            }
        });
    }

    private @NonNull Map<String, Object> fetchAll() {
        Map<String, Object> loaded = new HashMap<>();
        try {
            var results = DB.getResults("SELECT state_key, state_value FROM wake_state");
            for (var row : results) {
                Object value = gson.fromJson(row.getString("state_value"), Object.class);
                if (value != null) {
                    loaded.put(row.getString("state_key"), value);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load state from database", e);
        }
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
        state.put(key, value);
        asyncUpdate("Failed to save state", "REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)", key, gson.toJson(value));
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
        state.put(key, value);
        DB.executeUpdate("REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)", key, gson.toJson(value));
    }

    public Map<String, Object> snapshot(String prefix) {
        Map<String, Object> out = new HashMap<>();
        state.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k, v);
            }
        });
        return out;
    }

    public void clearPrefix(String prefix) {
        state.keySet().removeIf(k -> k.startsWith(prefix));
        asyncUpdate("Failed to clear state", "DELETE FROM wake_state WHERE state_key LIKE ?", prefix + "%");
    }
}
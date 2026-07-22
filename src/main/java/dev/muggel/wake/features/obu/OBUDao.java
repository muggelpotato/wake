package dev.muggel.wake.features.obu;

import co.aikar.idb.DB;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUContext.ContextType;
import dev.muggel.wake.features.obu.context.OBUPlayerState;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.core.database.WakeDao;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class OBUDao extends WakeDao {
    private static final Gson GSON = new Gson();
    public OBUDao(Wake plugin) {
        super(plugin);
    }

    @Override
    protected String syncScope() {
        return "obu";
    }

    @Override
    protected Map<String, String> getTableSchemas() {
        Map<String, String> schemas = new HashMap<>();
        schemas.put("wake_obu_contexts", """
                CREATE TABLE IF NOT EXISTS wake_obu_contexts (
                    name VARCHAR(255) PRIMARY KEY,
                    type VARCHAR(32) DEFAULT 'SERVER',
                    owner_uuid VARCHAR(36) NULL,
                    last_accessed_at BIGINT DEFAULT 0
                );
                """);
        schemas.put("wake_obu_settings", """
                CREATE TABLE IF NOT EXISTS wake_obu_settings (
                    context_name VARCHAR(255) NOT NULL,
                    unique_key VARCHAR(255) NOT NULL,
                    definition_name VARCHAR(255) NOT NULL,
                    args TEXT NOT NULL,
                    PRIMARY KEY (context_name, unique_key)
                );
                """);
        schemas.put("wake_obu_player_states", """
                CREATE TABLE IF NOT EXISTS wake_obu_player_states (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    active_sandbox VARCHAR(255) NULL,
                    active_context VARCHAR(255) NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
                """);
        return schemas;
    }

    @Override
    public void initTables() {
        super.initTables();
        try {
            DB.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sandbox_purge ON wake_obu_contexts(type, last_accessed_at)");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create index idx_sandbox_purge", e);
        }
    }

    @Contract(pure = true)
    private static @NonNull String canonical(@NonNull String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public boolean hasAnyContexts() {
        try {
            return DB.getFirstColumn("SELECT name FROM wake_obu_contexts LIMIT 1") != null;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to probe OBU contexts", e);
            return false;
        }
    }

    public Map<String, OBUContext> loadAllContexts() {
        Map<String, OBUContext> contexts = new HashMap<>();
        try {
            Map<String, List<OBUSetting>> settingsByContext = new HashMap<>();
            for (var row : DB.getResults("SELECT * FROM wake_obu_settings")) {
                OBUDefinition def = OBUDefinition.get(row.getString("definition_name"));
                if (def == null) continue;
                List<String> argsList = GSON.fromJson(row.getString("args"), new TypeToken<List<String>>(){}.getType());
                settingsByContext.computeIfAbsent(canonical(row.getString("context_name")), k -> new ArrayList<>())
                        .add(new OBUSetting(def, argsList));
            }
            for (var row : DB.getResults("SELECT * FROM wake_obu_contexts")) {
                String name = canonical(row.getString("name"));
                String ownerStr = row.getString("owner_uuid");
                UUID owner;
                ContextType type;
                try {
                    owner = ownerStr != null ? UUID.fromString(ownerStr) : null;
                    type = ContextType.valueOf(row.getString("type"));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping malformed OBU context row '" + name + "': " + e.getMessage());
                    continue;
                }
                contexts.put(name, new OBUContext(name, type, owner,
                        settingsByContext.getOrDefault(name, List.of())));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load OBU contexts", e);
        }
        return contexts;
    }

    public void saveContext(String name, @NonNull ContextType type, UUID ownerUuid) {
        String ownerStr = ownerUuid != null ? ownerUuid.toString() : null;
        asyncUpdate("Failed to save OBU context async",
                "REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES (?, ?, ?, ?)",
                canonical(name), type.name(), ownerStr, System.currentTimeMillis());
    }

    public void deleteContext(String name) {
        asyncUpdate("Failed to delete OBU settings async",
                "DELETE FROM wake_obu_settings WHERE context_name = ?", canonical(name));
        asyncUpdate("Failed to delete OBU context async",
                "DELETE FROM wake_obu_contexts WHERE name = ?", canonical(name));
    }

    public void importContextData(String name, @NonNull ContextType type, String ownerUuid, @NonNull List<OBUSetting> settings) throws SQLException {
        String canonicalName = canonical(name);
        DB.executeUpdate("DELETE FROM wake_obu_settings WHERE context_name = ?", canonicalName);
        DB.executeUpdate("DELETE FROM wake_obu_contexts WHERE name = ?", canonicalName);
        DB.executeUpdate("REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES (?, ?, ?, ?)",
                canonicalName, type.name(), ownerUuid, System.currentTimeMillis());

        for (OBUSetting setting : settings) {
            DB.executeUpdate("REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES (?, ?, ?, ?)",
                    canonicalName, setting.getUniqueKey(), setting.definition().name(), GSON.toJson(setting.args()));
        }
    }

    public void saveSetting(String contextName, @NonNull OBUSetting setting) {
        asyncUpdate("Failed to save OBU setting async",
                "REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES (?, ?, ?, ?)",
                canonical(contextName), setting.getUniqueKey(), setting.definition().name(), GSON.toJson(setting.args()));
    }

    public void deleteSetting(String contextName, String uniqueKey) {
        asyncUpdate("Failed to delete OBU setting async",
                "DELETE FROM wake_obu_settings WHERE context_name = ? AND unique_key = ?",
                canonical(contextName), uniqueKey);
    }

    public void savePlayerState(UUID uuid, String activeSandbox, String activeContext) {
        if (activeSandbox == null && activeContext == null) {
            asyncUpdateFor(uuid, "Failed to clear player state async",
                    "DELETE FROM wake_obu_player_states WHERE player_uuid = ?", uuid.toString());
            return;
        }
        asyncUpdateFor(uuid, "Failed to save player state async",
                "REPLACE INTO wake_obu_player_states (player_uuid, active_sandbox, active_context) VALUES (?, ?, ?)",
                uuid.toString(), activeSandbox, activeContext);
    }

    @Nullable
    public OBUPlayerState getPlayerState(UUID uuid) {
        if (plugin.getDatabaseManager().isDegraded()) {
            return null;
        }
        try {
            var row = DB.getFirstRow("SELECT active_sandbox, active_context FROM wake_obu_player_states WHERE player_uuid = ?", uuid.toString());
            if (row != null) {
                return new OBUPlayerState(
                        row.getString("active_sandbox"),
                        row.getString("active_context")
                );
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player state", e);
        }
        return null;
    }

    public void updateSandboxAccessTime(String name) {
        asyncUpdateLocal("Failed to update sandbox access time async",
                "UPDATE wake_obu_contexts SET last_accessed_at = ? WHERE name = ?",
                System.currentTimeMillis(), canonical(name));
    }

    public List<String> getOldSandboxes(long cutoffTimeMillis) {
        List<String> oldSandboxes = new ArrayList<>();
        try {
            var results = DB.getResults("SELECT name FROM wake_obu_contexts WHERE type = 'SANDBOX' AND last_accessed_at < ?", cutoffTimeMillis);
            for (var row : results) {
                oldSandboxes.add(row.getString("name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to fetch old sandboxes", e);
        }
        return oldSandboxes;
    }
}
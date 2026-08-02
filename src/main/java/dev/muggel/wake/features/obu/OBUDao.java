package dev.muggel.wake.features.obu;

import co.aikar.idb.DB;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContext.ContextType;
import dev.muggel.wake.features.obu.contexts.OBUPlayerState;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import co.aikar.idb.DbRow;
import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.database.SqlStatement;
import dev.muggel.wake.core.database.WakeDao;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class OBUDao extends WakeDao {
    private static final String UPSERT_CONTEXT = "REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES (?, ?, ?, ?)";
    private static final Gson GSON = new Gson();
    private final CachedStore<OBUContext> contexts = mirror("wake_obu_contexts", this::loadContexts);
    public OBUDao(@NonNull Wake plugin) {
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

    private static @NonNull String canonical(@NonNull String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public @NonNull CachedStore<OBUContext> contexts() {
        return contexts;
    }

    public @Nullable Boolean hasAnyContexts() {
        return read("wake_obu_contexts", () -> DB.getFirstColumn("SELECT name FROM wake_obu_contexts LIMIT 1") != null);
    }

    public @Nullable Map<String, OBUContext> loadContexts(@Nullable Set<String> keys) {
        return read("wake_obu_contexts", () -> {
            Map<String, OBUContext> found = new HashMap<>();
            Map<String, List<OBUSetting>> settingsByContext = new HashMap<>();
            List<DbRow> contextRows = new ArrayList<>();
            selectByKeys("SELECT * FROM wake_obu_contexts", "name", keys, contextRows::add);
            selectByKeys("SELECT context_name, definition_name, args FROM wake_obu_settings", "context_name", keys, row -> {
                OBUDefinition def = OBUDefinition.get(row.getString("definition_name"));
                if (def == null) return;
                List<String> argsList = GSON.fromJson(row.getString("args"), new TypeToken<List<String>>(){}.getType());
                settingsByContext.computeIfAbsent(canonical(row.getString("context_name")), k -> new ArrayList<>())
                        .add(new OBUSetting(def, argsList));
            });
            for (DbRow row : contextRows) {
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
                found.put(name, new OBUContext(name, type, owner,
                        settingsByContext.getOrDefault(name, List.of())));
            }
            found.put(OBUDefinition.CONTEXT_EMPTY,
                    new OBUContext(OBUDefinition.CONTEXT_EMPTY, ContextType.SERVER, null, List.of()));
            return found;
        });
    }

    public void saveContext(@NonNull OBUContext context, @NonNull List<SqlStatement> extraStatements) {
        String name = canonical(context.name());
        String ownerStr = context.ownerUuid() != null ? context.ownerUuid().toString() : null;
        List<SqlStatement> statements = new ArrayList<>();
        statements.add(new SqlStatement(UPSERT_CONTEXT,
                new Object[]{name, context.type().name(), ownerStr, System.currentTimeMillis()}));
        statements.addAll(extraStatements);
        contexts.save(name, context, "Failed to save OBU context", statements);
    }

    public void deleteContext(@NonNull String name) {
        String canonicalName = canonical(name);
        contexts.delete(canonicalName, "Failed to delete OBU context", deleteStatements(canonicalName));
    }

    public void renameContext(@NonNull String fromName, @NonNull OBUContext to) {
        String from = canonical(fromName);
        String toName = canonical(to.name());
        String ownerStr = to.ownerUuid() != null ? to.ownerUuid().toString() : null;
        List<SqlStatement> statements = new ArrayList<>(deleteStatements(from));
        statements.add(new SqlStatement(UPSERT_CONTEXT,
                new Object[]{toName, to.type().name(), ownerStr, System.currentTimeMillis()}));
        for (OBUSetting setting : to.settings()) {
            statements.add(settingUpsert(toName, setting));
        }
        contexts.moveKey(from, toName, to, "Failed to rename OBU context async", statements);
    }

    private static @NonNull List<SqlStatement> deleteStatements(String canonicalName) {
        return List.of(
                new SqlStatement("DELETE FROM wake_obu_settings WHERE context_name = ?", new Object[]{canonicalName}),
                new SqlStatement("DELETE FROM wake_obu_contexts WHERE name = ?", new Object[]{canonicalName}));
    }

    public @NonNull SqlStatement settingUpsert(@NonNull String contextName, @NonNull OBUSetting setting) {
        return new SqlStatement("REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES (?, ?, ?, ?)",
                new Object[]{canonical(contextName), setting.getUniqueKey(), setting.definition().name(), GSON.toJson(setting.args())});
    }

    public @NonNull SqlStatement settingDelete(@NonNull String contextName, @NonNull String uniqueKey) {
        return new SqlStatement("DELETE FROM wake_obu_settings WHERE context_name = ? AND unique_key = ?",
                new Object[]{canonical(contextName), uniqueKey});
    }

    public void importContextData(@NonNull String name, @NonNull ContextType type, @Nullable String ownerUuid, @NonNull List<OBUSetting> settings) throws SQLException {
        String canonicalName = canonical(name);
        for (SqlStatement delete : deleteStatements(canonicalName)) {
            DB.executeUpdate(delete.sql(), delete.params());
        }
        DB.executeUpdate(UPSERT_CONTEXT, canonicalName, type.name(), ownerUuid, System.currentTimeMillis());

        for (OBUSetting setting : settings) {
            SqlStatement upsert = settingUpsert(canonicalName, setting);
            DB.executeUpdate(upsert.sql(), upsert.params());
        }
        UUID owner = null;
        try {
            owner = ownerUuid != null ? UUID.fromString(ownerUuid) : null;
        } catch (IllegalArgumentException ignored) {
        }
        contexts.announce(canonicalName, new OBUContext(canonicalName, type, owner, settings));
    }

    public void savePlayerState(@NonNull UUID uuid, @Nullable String activeSandbox, @Nullable String activeContext) {
        if (activeSandbox == null && activeContext == null) {
            asyncUpdateFor(uuid, "Failed to clear player state",
                    "DELETE FROM wake_obu_player_states WHERE player_uuid = ?", uuid.toString());
            return;
        }
        asyncUpdateFor(uuid, "Failed to save player state",
                "REPLACE INTO wake_obu_player_states (player_uuid, active_sandbox, active_context) VALUES (?, ?, ?)",
                uuid.toString(), activeSandbox, activeContext);
    }

    public @Nullable OBUPlayerState getPlayerState(@NonNull UUID uuid) {
        if (plugin.getDatabaseManager().isDegraded()) {
            return null;
        }
        return read("player state", () -> {
            var row = DB.getFirstRow("SELECT active_sandbox, active_context FROM wake_obu_player_states WHERE player_uuid = ?", uuid.toString());
            return row == null
                    ? new OBUPlayerState(null, null)
                    : new OBUPlayerState(row.getString("active_sandbox"), row.getString("active_context"));
        });
    }

    public void updateSandboxAccessTime(@NonNull String name) {
        asyncUpdateLocal("Failed to update sandbox access time",
                "UPDATE wake_obu_contexts SET last_accessed_at = ? WHERE name = ?",
                System.currentTimeMillis(), canonical(name));
    }

    public @Nullable List<String> getOldSandboxes(long cutoffTimeMillis) {
        if (plugin.getDatabaseManager().isDegraded()) {
            return null;
        }
        return read("old sandboxes", () -> {
            List<String> oldSandboxes = new ArrayList<>();
            var results = DB.getResults("SELECT name FROM wake_obu_contexts WHERE type = 'SANDBOX' AND last_accessed_at < ?", cutoffTimeMillis);
            for (var row : results) {
                oldSandboxes.add(row.getString("name"));
            }
            return oldSandboxes;
        });
    }
}
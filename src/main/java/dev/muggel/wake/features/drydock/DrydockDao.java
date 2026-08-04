package dev.muggel.wake.features.drydock;

import co.aikar.idb.DB;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;

import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.database.SqlStatement;
import dev.muggel.wake.core.database.WakeDao;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DrydockDao extends WakeDao {
    private static final String UPSERT_BOOSTPAD = "REPLACE INTO wake_drydock_boostpads (block_key, enabled, force_x, force_y, force_z, delay_ms, padding) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private final CachedStore<BoostpadConfig> boostpads = mirror("wake_drydock_boostpads", this::readBoostpads);
    public DrydockDao(Wake plugin) {
        super(plugin);
    }

    @Override
    protected String syncScope() {
        return "drydock";
    }

    @Override
    protected Map<String, String> getTableSchemas() {
        return Map.of("wake_drydock_boostpads", """
                CREATE TABLE IF NOT EXISTS wake_drydock_boostpads (
                    block_key VARCHAR(255) PRIMARY KEY,
                    enabled BOOLEAN NOT NULL,
                    force_x DOUBLE NOT NULL,
                    force_y DOUBLE NOT NULL,
                    force_z DOUBLE NOT NULL,
                    delay_ms BIGINT NOT NULL,
                    padding DOUBLE NOT NULL
                );
                """);
    }

    public @NonNull CachedStore<BoostpadConfig> boostpads() {
        return boostpads;
    }

    private @NonNull Map<String, BoostpadConfig> readBoostpads(@Nullable Set<String> keys) throws SQLException {
        Map<String, BoostpadConfig> pads = new HashMap<>();
        selectByKeys("SELECT * FROM wake_drydock_boostpads", "block_key", keys, row -> {
            String key = row.getString("block_key");
            pads.put(key, new BoostpadConfig(
                    key,
                    toBoolean(row.get("enabled")),
                    ((Number) row.get("force_x")).doubleValue(),
                    ((Number) row.get("force_y")).doubleValue(),
                    ((Number) row.get("force_z")).doubleValue(),
                    ((Number) row.get("delay_ms")).longValue(),
                    ((Number) row.get("padding")).doubleValue()
            ));
        });
        return pads;
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(raw)) || "true".equalsIgnoreCase(String.valueOf(raw));
    }

    public void saveBoostpad(@NonNull BoostpadConfig config) {
        boostpads.save(config.blockKey(), config, "Failed to save boostpad async", List.of(new SqlStatement(UPSERT_BOOSTPAD, upsertParams(config))));
    }

    public void importBoostpad(@NonNull BoostpadConfig config) throws SQLException {
        DB.executeUpdate(UPSERT_BOOSTPAD, upsertParams(config));
        boostpads.announce(config.blockKey(), config);
    }

    public void deleteBoostpad(String blockKey) {
        boostpads.delete(blockKey, "Failed to delete boostpad async", List.of(new SqlStatement("DELETE FROM wake_drydock_boostpads WHERE block_key = ?", new Object[]{blockKey})));
    }

    private static Object @NonNull [] upsertParams(@NonNull BoostpadConfig config) {
        return new Object[]{config.blockKey(), config.enabled(), config.forceX(), config.forceY(),
                config.forceZ(), config.delayMs(), config.padding()};
    }
}
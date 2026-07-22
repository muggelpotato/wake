package dev.muggel.wake.features.drydock;

import co.aikar.idb.DB;
import dev.muggel.wake.Wake;
import dev.muggel.wake.features.drydock.api.BoostpadConfig;

import dev.muggel.wake.core.database.WakeDao;
import org.jspecify.annotations.NonNull;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class DrydockDao extends WakeDao {
    private static final String UPSERT_BOOSTPAD =
            "REPLACE INTO wake_drydock_boostpads (block_key, enabled, force_x, force_y, force_z, delay_ms, hitbox_percent) VALUES (?, ?, ?, ?, ?, ?, ?)";
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
                    hitbox_percent INT NOT NULL
                );
                """);
    }

    public Map<String, BoostpadConfig> loadAllBoostpads() {
        Map<String, BoostpadConfig> pads = new ConcurrentHashMap<>();
        try {
            var results = DB.getResults("SELECT * FROM wake_drydock_boostpads");
            for (var row : results) {
                String key = row.getString("block_key");
                pads.put(key, new BoostpadConfig(
                        key,
                        toBoolean(row.get("enabled")),
                        ((Number) row.get("force_x")).doubleValue(),
                        ((Number) row.get("force_y")).doubleValue(),
                        ((Number) row.get("force_z")).doubleValue(),
                        ((Number) row.get("delay_ms")).longValue(),
                        ((Number) row.get("hitbox_percent")).intValue()
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load boostpads from database", e);
        }
        return pads;
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        return "1".equals(String.valueOf(raw)) || "true".equalsIgnoreCase(String.valueOf(raw));
    }

    public void saveBoostpad(@NonNull BoostpadConfig config) {
        asyncUpdate("Failed to save boostpad async", UPSERT_BOOSTPAD,
                config.blockKey(), config.enabled(), config.forceX(), config.forceY(), config.forceZ(), config.delayMs(), config.hitboxPercent());
    }

    public void importBoostpad(@NonNull BoostpadConfig config) throws SQLException {
        DB.executeUpdate(UPSERT_BOOSTPAD,
                config.blockKey(), config.enabled(), config.forceX(), config.forceY(), config.forceZ(), config.delayMs(), config.hitboxPercent());
    }

    public void deleteBoostpad(String blockKey) {
        asyncUpdate("Failed to delete boostpad async",
                "DELETE FROM wake_drydock_boostpads WHERE block_key = ?", blockKey);
    }
}
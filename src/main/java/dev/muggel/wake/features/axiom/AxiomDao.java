package dev.muggel.wake.features.axiom;

import co.aikar.idb.DB;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.database.WakeDao;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AxiomDao extends WakeDao {
    private final CachedStore<String> displays = mirror("wake_axiom_displays", this::readDisplays);
    public AxiomDao(Wake plugin) {
        super(plugin);
    }

    @Override
    protected String syncScope() {
        return "axiom";
    }

    @Override
    protected Map<String, String> getTableSchemas() {
        return Map.of("wake_axiom_displays", """
                CREATE TABLE IF NOT EXISTS wake_axiom_displays (
                    model_key VARCHAR(255) PRIMARY KEY
                );
                """);
    }

    public @NonNull CachedStore<String> displays() {
        return displays;
    }

    private @NonNull Map<String, String> readDisplays(@Nullable Set<String> keys) throws SQLException {
        Map<String, String> models = new HashMap<>();
        selectByKeys("SELECT model_key FROM wake_axiom_displays", "model_key", keys, row -> {
            String key = row.getString("model_key");
            models.put(key, key);
        });
        return models;
    }

    public void importDisplay(String modelKey) throws SQLException {
        DB.executeUpdate("REPLACE INTO wake_axiom_displays (model_key) VALUES (?)", modelKey);
        displays.announce(modelKey, modelKey);
    }
}
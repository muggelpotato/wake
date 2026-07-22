package dev.muggel.wake.features.axiom;

import co.aikar.idb.DB;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.WakeDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class AxiomDao extends WakeDao {
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

    public List<String> loadDisplays() {
        List<String> models = new ArrayList<>();
        try {
            var results = DB.getResults("SELECT model_key FROM wake_axiom_displays");
            for (var row : results) {
                models.add(row.getString("model_key"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load axiom displays from database", e);
        }
        return models;
    }

    public void importDisplay(String modelKey) throws SQLException {
        DB.executeUpdate("REPLACE INTO wake_axiom_displays (model_key) VALUES (?)", modelKey);
    }
}
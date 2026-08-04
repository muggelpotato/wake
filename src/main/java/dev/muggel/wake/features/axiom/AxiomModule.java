package dev.muggel.wake.features.axiom;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.features.axiom.integration.AxiomDisplays;
import org.jspecify.annotations.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class AxiomModule extends WakeModule {
    private AxiomDao dao;
    private AxiomDisplays axiomDisplays;
    public AxiomModule(Wake plugin) {
        super(plugin, "axiom");
    }

    @Override
    public boolean isCompatible() {
        return AxiomDisplays.isAvailable();
    }

    @Override
    protected void onModuleEnable() {
        dao = registerDao(new AxiomDao(plugin));
        dao.initTables();
        axiomDisplays = new AxiomDisplays(plugin);
        CachedStore<String> displays = dao.displays();
        boolean read = displays.load();
        axiomDisplays.register(Set.copyOf(displays.keys()));
        seedDataIfEmpty(read && displays.keys().isEmpty());
    }

    @Override
    protected void onModuleDisable() {
        if (axiomDisplays != null) {
            axiomDisplays.unregisterAll();
        }
        axiomDisplays = null;
        dao = null;
    }

    @Override
    public void reload() {
        AxiomDao currentDao = this.dao;
        AxiomDisplays displays = this.axiomDisplays;
        if (currentDao == null || displays == null || !isCompatible()) return;
        currentDao.displays().reloadAsync(ignored -> {
            if (this.dao != currentDao) return;
            displays.unregisterAll();
            displays.register(Set.copyOf(currentDao.displays().keys()));
        });
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        AxiomDao currentDao = this.dao;
        if (currentDao != null && !currentDao.displays().isLoaded()) {
            throw new SQLException("Axiom displays could not be read");
        }
        int count = exportState(yaml);
        if (currentDao == null) {
            return count;
        }
        List<String> models = List.copyOf(currentDao.displays().keys());
        yaml.set("displays", models);
        return count + models.size();
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        AxiomDao currentDao = this.dao;
        int count = 0;
        if (currentDao != null) {
            for (String display : yaml.getStringList("displays")) {
                try {
                    currentDao.importDisplay(display);
                    count++;
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to import axiom display " + display, e);
                }
            }
        }
        count += importState(yaml);
        reload();
        return count;
    }
}
package dev.muggel.wake.features.axiom;

import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.axiom.integration.AxiomDisplays;
import org.jspecify.annotations.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class AxiomModule extends AbstractModule {
    private AxiomDao dao;
    private AxiomDisplays axiomDisplays;
    public AxiomModule() {
        super("axiom");
    }

    @Override
    public boolean isCompatible() {
        return AxiomDisplays.isAvailable();
    }

    @Override
    protected void onModuleEnable() {
        dao = new AxiomDao(getPlugin());
        dao.initTables();
        registerDao(dao);
        axiomDisplays = new AxiomDisplays(getPlugin());
        CachedStore<String> displays = dao.displays();
        boolean read = displays.load();
        axiomDisplays.register(Set.copyOf(displays.keys()));
        seedDataIfEmpty(read ? displays.keys().isEmpty() : null, "defaults/axiom_default.yml", "Axiom Displays");
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
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        currentDao.displays().reloadAsync(ignored -> {
            if (this.dao != currentDao) return;
            displays.unregisterAll();
            displays.register(Set.copyOf(currentDao.displays().keys()));
        });
    }

    @Override
    protected int onExportData(YamlConfiguration yaml) throws SQLException {
        if (dao == null) return 0;
        CachedStore<String> displays = dao.displays();
        if (!displays.isLoaded()) {
            throw new SQLException("Axiom displays could not be read");
        }
        List<String> models = List.copyOf(displays.keys());
        yaml.set("displays", models);
        return models.size();
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) {
        List<String> displays = yaml.getStringList("displays");
        if (displays.isEmpty()) return 0;
        if (dao == null) return 0;
        int count = 0;
        for (String display : displays) {
            try {
                dao.importDisplay(display);
                count++;
            } catch (SQLException e) {
                getPlugin().getLogger().log(Level.SEVERE, "Failed to import axiom display " + display, e);
            }
        }
        reload();
        return count;
    }
}
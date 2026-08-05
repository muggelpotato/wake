package dev.muggel.wake.features.axiom;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.database.CachedStore;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.features.axiom.integration.AxiomDisplays;
import org.jspecify.annotations.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;

import java.sql.SQLException;
import java.util.List;

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
        axiomDisplays = new AxiomDisplays(plugin);
        dao = registerDao(new AxiomDao(plugin));
        dao.initTables();
        CachedStore<String> displays = dao.displays();
        boolean read = displays.load();
        axiomDisplays.apply(displays.keys());
        if (!read) {
            reload();
        }
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
        if (currentDao == null || displays == null) return;
        currentDao.displays().reloadAsync(ignored -> {
            if (this.dao == currentDao) {
                displays.apply(currentDao.displays().keys());
            }
        });
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        AxiomDao currentDao = this.dao;
        if (currentDao == null || !currentDao.displays().isLoaded()) {
            throw new SQLException("Axiom displays could not be read");
        }
        int count = exportState(yaml);
        List<String> models = currentDao.displays().keys().stream().sorted().toList();
        yaml.set("displays", models);
        return count + models.size();
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        List<String> models = yaml.getStringList("displays");
        for (String model : models) {
            dao.importDisplay(model);
        }
        int count = models.size() + importState(yaml);
        reload();
        return count;
    }
}
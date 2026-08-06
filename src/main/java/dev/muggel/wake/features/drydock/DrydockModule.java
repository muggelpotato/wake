package dev.muggel.wake.features.drydock;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.features.drydock.commands.boostpad.BoostpadCommand;
import dev.muggel.wake.features.drydock.commands.GetBoatCommand;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.integration.OBUBoostpadIntegration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class DrydockModule extends WakeModule {
    private DrydockDao drydockDao;
    private volatile @Nullable BoostpadRegistry boostpads;
    private @Nullable BoostpadDetectorListener detectorListener;
    public DrydockModule(Wake plugin) {
        super(plugin, "drydock");
    }

    @Override
    protected void onModuleEnable() {
        this.drydockDao = registerDao(new DrydockDao(plugin));
        drydockDao.initTables();
        BoostpadRegistry registry = new BoostpadRegistry(drydockDao);
        BoostpadDetectorListener detector = new BoostpadDetectorListener(plugin, registry);
        this.boostpads = registry;
        this.detectorListener = detector;
        registry.setOnReloadCallback(detector::updateRegistration);
        detector.updateRegistration();
        registerListener(new OBUBoostpadIntegration(plugin));
        seedDataIfEmpty(() -> (registry.isLoaded() || registry.load()) ? registry.cachedBoostpads().isEmpty() : null);
    }

    public void refreshBoostpadRegistration() {
        BoostpadDetectorListener detector = this.detectorListener;
        if (detector != null) {
            detector.updateRegistration();
        }
    }

    @Override
    public CommandNode buildCommands() {
        return CommandNode.literal("drydock")
                .withPreset(PermissionPreset.BUILDER)
                .aliases("dd")
                .addSubcommand(BoostpadCommand.getNode(plugin))
                .addSubcommand(GetBoatCommand.getNode(plugin));
    }

    @Override
    protected void onModuleDisable() {
        BoostpadRegistry registry = this.boostpads;
        if (registry != null) {
            registry.setOnReloadCallback(null);
        }
        BoostpadDetectorListener detector = this.detectorListener;
        if (detector != null) {
            detector.unregister();
        }
        detectorListener = null;
        boostpads = null;
        drydockDao = null;
    }

    public @Nullable BoostpadRegistry getBoostpads() {
        return boostpads;
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        BoostpadRegistry registry = this.boostpads;
        if (registry == null || !registry.isLoaded()) {
            throw new SQLException("Drydock boostpads could not be read");
        }
        int count = exportState(yaml);
        for (Map.Entry<String, BoostpadConfig> entry : registry.cachedBoostpads().entrySet()) {
            entry.getValue().writeTo(yaml.createSection("boostpads." + entry.getKey()));
            count++;
        }
        return count;
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        ConfigurationSection padsSec = yaml.getConfigurationSection("boostpads");
        int count = 0;
        if (padsSec != null) {
            for (String key : padsSec.getKeys(false)) {
                ConfigurationSection padSec = padsSec.getConfigurationSection(key);
                if (padSec == null) {
                    plugin.getLogger().warning("Skipped boostpad " + key + ": it is not a block of settings");
                    continue;
                }
                try {
                    drydockDao.importBoostpad(BoostpadConfig.read(key, padSec));
                    count++;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to import boostpad " + key, e);
                }
            }
        }
        count += importState(yaml);
        reload();
        return count;
    }

    @Override
    public void reload() {
        BoostpadRegistry registry = this.boostpads;
        if (registry != null) {
            registry.reloadBoostpads();
        }
    }
}
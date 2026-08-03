package dev.muggel.wake.features.drydock;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.drydock.commands.boostpad.BoostpadCommand;
import dev.muggel.wake.features.drydock.commands.GetBoatCommand;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.integration.OBUBoostpadIntegration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Map;
import java.util.logging.Level;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class DrydockModule extends AbstractModule {
    private DrydockDao drydockDao;
    private BoostpadRegistry boostpads;
    private BoostpadDetectorListener detectorListener;
    public DrydockModule() {
        super("drydock");
    }

    @Override
    protected void onModuleEnable() {
        this.drydockDao = new DrydockDao(getPlugin());
        drydockDao.initTables();
        registerDao(drydockDao);
        this.boostpads = new BoostpadRegistry(getPlugin(), drydockDao);
        this.detectorListener = new BoostpadDetectorListener(getPlugin(), boostpads);
        boostpads.setOnReloadCallback(this.detectorListener::updateRegistration);
        registerListener(new OBUBoostpadIntegration(getPlugin()));
        seedDataIfEmpty(boostpads.isLoaded() ? boostpads.cachedBoostpads().isEmpty() : null, "defaults/drydock_default.yml", "Drydock");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        return CommandNode.literal("drydock")
                .withModule(DrydockModule.class)
                .withPreset(PermissionPreset.BUILDER)
                .withDescription("Commands for the Drydock server")
                .aliases("dd")
                .addSubcommand(BoostpadCommand.getNode(plugin))
                .addSubcommand(GetBoatCommand.getNode(plugin));
    }

    @Override
    protected void onModuleDisable() {
        if (boostpads != null) {
            boostpads.setOnReloadCallback(null);
        }
        if (detectorListener != null) {
            detectorListener.unregister();
            detectorListener = null;
        }
        boostpads = null;
        drydockDao = null;
    }

    public @Nullable BoostpadRegistry getBoostpads() {
        return boostpads;
    }

    @Override
    protected int onExportData(YamlConfiguration yaml) {
        BoostpadRegistry registry = this.boostpads;
        int count = exportState(yaml);
        if (registry == null) {
            return count;
        }
        for (Map.Entry<String, BoostpadConfig> entry : registry.cachedBoostpads().entrySet()) {
            String key = entry.getKey();
            BoostpadConfig config = entry.getValue();
            String path = "boostpads." + key;
            yaml.set(path + ".enabled", config.enabled());
            yaml.set(path + ".force_x", config.forceX());
            yaml.set(path + ".force_y", config.forceY());
            yaml.set(path + ".force_z", config.forceZ());
            yaml.set(path + ".delay_ms", config.delayMs());
            yaml.set(path + ".padding", config.padding());
            count++;
        }
        return count + exportState(yaml);
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) {
        ConfigurationSection padsSec = yaml.getConfigurationSection("boostpads");
        int count = 0;
        if (padsSec != null) {
            for (String key : padsSec.getKeys(false)) {
                boolean enabled = padsSec.getBoolean(key + ".enabled", true);
                double forceX = padsSec.getDouble(key + ".force_x", 0);
                double forceY = padsSec.getDouble(key + ".force_y", 0);
                double forceZ = padsSec.getDouble(key + ".force_z", 0);
                long delayMs = padsSec.getLong(key + ".delay_ms", 1000);
                double padding = padsSec.getDouble(key + ".padding", BoostpadConfig.DEFAULT_PADDING);
                BoostpadConfig config = new BoostpadConfig(key, enabled, forceX, forceY, forceZ, delayMs, padding);
                try {
                    drydockDao.importBoostpad(config);
                    count++;
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.SEVERE, "Failed to import boostpad " + key, e);
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
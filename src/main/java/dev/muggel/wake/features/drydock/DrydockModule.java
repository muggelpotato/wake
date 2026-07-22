package dev.muggel.wake.features.drydock;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.drydock.api.DrydockService;
import dev.muggel.wake.features.drydock.commands.boostpad.BoostpadCommand;
import dev.muggel.wake.features.drydock.commands.GetBoatCommand;
import dev.muggel.wake.features.drydock.integration.obu.OBUBoostpadIntegration;
import dev.muggel.wake.features.drydock.listeners.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.service.DrydockServiceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Map;
import java.util.logging.Level;
import dev.muggel.wake.features.drydock.api.BoostpadConfig;
import org.jspecify.annotations.NonNull;

public class DrydockModule extends AbstractModule {
    private DrydockDao drydockDao;
    private BoostpadDetectorListener detectorListener;
    public DrydockModule() {
        super("drydock");
    }

    @Override
    protected void onModuleEnable() {
        this.drydockDao = new DrydockDao(getPlugin());
        drydockDao.initTables();
        registerDao(drydockDao);
        DrydockService drydockService = new DrydockServiceImpl(getPlugin(), drydockDao);
        boolean wasEmpty = drydockService.cachedBoostpads().isEmpty();
        Wake.getServiceRegistry().register(DrydockService.class, drydockService);
        this.detectorListener = new BoostpadDetectorListener(getPlugin(), drydockService);
        drydockService.setOnReloadCallback(this.detectorListener::updateRegistration);
        registerListener(new OBUBoostpadIntegration());
        seedDataIfEmpty(wasEmpty, "drydock_default.yml", "Drydock");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        return CommandNode.literal("drydock")
                .withModule(DrydockModule.class)
                .withDescription("Commands for the Drydock server")
                .aliases("dd")
                .addSubcommand(BoostpadCommand.getNode(plugin))
                .addSubcommand(GetBoatCommand.getNode(plugin));
    }

    @Override
    protected void onModuleDisable() {
        if (detectorListener != null) {
            detectorListener.unregister();
            detectorListener = null;
        }
        Wake.getServiceRegistry().unregister(DrydockService.class);
        drydockDao = null;
    }

    @Override
    protected int onExportData(YamlConfiguration yaml) {
        DrydockService service = Wake.getServiceRegistry().get(DrydockService.class);
        if (service == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, BoostpadConfig> entry : service.cachedBoostpads().entrySet()) {
            String key = entry.getKey();
            BoostpadConfig config = entry.getValue();
            String path = "boostpads." + key;
            yaml.set(path + ".enabled", config.enabled());
            yaml.set(path + ".force_x", config.forceX());
            yaml.set(path + ".force_y", config.forceY());
            yaml.set(path + ".force_z", config.forceZ());
            yaml.set(path + ".delay_ms", config.delayMs());
            yaml.set(path + ".hitbox_percent", config.hitboxPercent());
            count++;
        }
        return count;
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
                int hitboxPercent = padsSec.getInt(key + ".hitbox_percent", 99);
                BoostpadConfig config = new BoostpadConfig(key, enabled, forceX, forceY, forceZ, delayMs, hitboxPercent);
                try {
                    drydockDao.importBoostpad(config);
                    count++;
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.SEVERE, "Failed to import boostpad " + key, e);
                }
            }
        }
        if (yaml.contains("boostpads_enabled") && !getPlugin().getStateDao().has(BoostpadCommand.STATE_KEY_ENABLED)) {
            getPlugin().getStateDao().set(BoostpadCommand.STATE_KEY_ENABLED, yaml.getBoolean("boostpads_enabled"));
        }
        reload();
        return count;
    }

    @Override
    public void reload() {
        DrydockService service = Wake.getServiceRegistry().get(DrydockService.class);
        if (service != null) {
            service.reloadBoostpads();
        }
    }
}
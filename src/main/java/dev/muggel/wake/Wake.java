package dev.muggel.wake;

import dev.muggel.wake.core.TickClock;
import dev.muggel.wake.core.VehiclePath;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.database.StateDao;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.core.module.ModuleManager;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.module.ServiceRegistry;
import dev.muggel.wake.core.sync.SyncService;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.base.BaseModule;
import dev.muggel.wake.features.axiom.AxiomModule;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Collections;
import dev.muggel.wake.core.database.DatabaseManager;
import java.util.logging.Level;

public final class Wake extends JavaPlugin {
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
    private ModuleManager moduleManager;
    private DatabaseManager databaseManager;
    private StateDao stateDao;
    private MessageManager messageManager;
    private SyncService syncService;
    private TickClock tickClock;
    private VehiclePath vehiclePath;
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    @Override
    public void onEnable() {
        initPacketEvents();
        saveDefaultConfig();
        this.databaseManager = new DatabaseManager(this);
        try {
            databaseManager.init();
            this.stateDao = new StateDao(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database initialization failed: disabling Wake", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.syncService = new SyncService(this);
        this.messageManager = new MessageManager(this);
        this.tickClock = new TickClock();
        this.vehiclePath = new VehiclePath(this);
        getServer().getPluginManager().registerEvents(tickClock, this);
        this.moduleManager = new ModuleManager(this, List.of(new BaseModule(this), new OBUModule(this), new DrydockModule(this), new AxiomModule(this)));
        moduleManager.declareCommands();
        WakeCommandManager.init(this);
        moduleManager.syncModules();
    }

    @SuppressWarnings("UnstableApiUsage")
    private void initPacketEvents() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().load();
        PacketEvents.getAPI().init();
    }

    @Override
    public void onDisable() {
        try {
            if (moduleManager != null) {
                moduleManager.disableAll();
                moduleManager = null;
            }
        } finally {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
            if (syncService != null) {
                syncService.shutdown();
                syncService = null;
            }
            databaseManager = null;
            stateDao = null;
            messageManager = null;
            tickClock = null;
            vehiclePath = null;
            try {
                PacketEvents.getAPI().terminate();
            } catch (IllegalStateException e) {
                getLogger().log(Level.WARNING, "Failed to terminate PacketEvents", e);
            }
            getLogger().info("Wake has been disabled");
        }
    }

    public List<Component> reloadSettings() {
        reloadConfig();
        if (messageManager != null) {
            messageManager.reload();
        }
        if (databaseManager != null) {
            databaseManager.invalidateAllMirrors();
        }
        List<Component> feedback = Collections.emptyList();
        if (moduleManager != null) {
            feedback = moduleManager.syncModules();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
        return feedback;
    }

    public <T extends WakeModule> @Nullable T getModule(Class<T> clazz) {
        return moduleManager != null ? moduleManager.getModule(clazz) : null;
    }

    public boolean isModuleActive(String moduleId) {
        return moduleManager != null && moduleManager.isActive(moduleId);
    }

    public List<WakeModule> getActiveModules() {
        return moduleManager != null ? moduleManager.getActiveModules() : Collections.emptyList();
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public StateDao getStateDao() {
        return stateDao;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public SyncService getSyncService() {
        return syncService;
    }

    public TickClock getTickClock() {
        return tickClock;
    }

    public VehiclePath getVehiclePath() {
        return vehiclePath;
    }
}
package dev.muggel.wake;

import com.github.retrooper.packetevents.PacketEvents;
import dev.muggel.wake.core.TickClock;
import dev.muggel.wake.core.VehiclePath;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.database.DatabaseManager;
import dev.muggel.wake.core.database.StateDao;
import dev.muggel.wake.core.module.ModuleManager;
import dev.muggel.wake.core.module.ServiceRegistry;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.sync.SyncService;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.features.axiom.AxiomModule;
import dev.muggel.wake.features.core.CoreModule;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.obu.OBUModule;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
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

    @Override
    public void onEnable() {
        initPacketEvents();
        if (!isEnabled()) {
            return;
        }
        saveDefaultConfig();
        this.databaseManager = new DatabaseManager(this);
        try {
            databaseManager.init();
            this.syncService = new SyncService(this);
            this.stateDao = new StateDao(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database initialization failed: disabling Wake", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.messageManager = new MessageManager(this);
        this.tickClock = new TickClock();
        this.vehiclePath = new VehiclePath(this);
        getServer().getPluginManager().registerEvents(tickClock, this);
        this.moduleManager = new ModuleManager(this, List.of(
                new CoreModule(this), new OBUModule(this), new DrydockModule(this), new AxiomModule(this)));
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
            }
        } finally {
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
            if (syncService != null) {
                syncService.shutdown();
            }
            PacketEvents.getAPI().terminate();
            getLogger().info("Wake has been disabled");
        }
    }

    public @NonNull List<Component> reloadSettings() {
        reloadConfig();
        messageManager.reload();
        databaseManager.invalidateAllMirrors();
        stateDao.load();
        List<Component> feedback = moduleManager.syncModules();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
        return feedback;
    }

    public <T extends WakeModule> @Nullable T getModule(@NonNull Class<T> clazz) {
        return moduleManager.getModule(clazz);
    }

    public boolean isModuleActive(@NonNull String moduleId) {
        return moduleManager.isActive(moduleId);
    }

    public void seedDeferredModules() {
        moduleManager.seedDeferred();
    }

    public @NonNull List<WakeModule> getActiveModules() {
        return moduleManager.getActiveModules();
    }

    public @NonNull ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public @NonNull DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public @NonNull StateDao getStateDao() {
        return stateDao;
    }

    public @NonNull MessageManager getMessageManager() {
        return messageManager;
    }

    public @NonNull SyncService getSyncService() {
        return syncService;
    }

    public @NonNull TickClock getTickClock() {
        return tickClock;
    }

    public @NonNull VehiclePath getVehiclePath() {
        return vehiclePath;
    }
}
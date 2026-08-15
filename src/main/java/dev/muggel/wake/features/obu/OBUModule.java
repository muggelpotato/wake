package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.commands.ClearCommand;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.commands.ConfigCommand;
import dev.muggel.wake.features.obu.commands.ContextCommand;
import dev.muggel.wake.features.obu.commands.DefaultsCommand;
import dev.muggel.wake.features.obu.commands.HelpCommand;
import dev.muggel.wake.features.obu.commands.SettingsCommand;
import dev.muggel.wake.features.obu.commands.StatusCommand;
import dev.muggel.wake.features.obu.commands.sandbox.SandboxCommand;
import dev.muggel.wake.features.obu.clients.HandshakeListener;
import dev.muggel.wake.features.obu.delivery.PacketSender;
import dev.muggel.wake.features.obu.clients.BoatLagInterceptor;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
import dev.muggel.wake.features.obu.delivery.BoatSyncListener;
import dev.muggel.wake.features.obu.contexts.SandboxPurger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class OBUModule extends WakeModule {
    public static final String ID = "obu";
    private OBUDao obuDao;
    private OBUContextManager contextManager;
    private ClientRegistry clients;
    private ActiveContexts active;
    private PacketSender packetSender;
    private OBUSyncManager syncManager;
    private ContextDelivery delivery;
    private SandboxPurger sandboxPurger;
    private OBUDataTransfer dataTransfer;
    public OBUModule(Wake plugin) {
        super(plugin, ID);
    }

    @Override
    protected void onModuleEnable() {
        OBUDao dao = registerDao(new OBUDao(plugin));
        this.obuDao = dao;
        obuDao.initTables();
        this.clients = new ClientRegistry();
        this.packetSender = new PacketSender(clients);
        this.contextManager = new OBUContextManager(obuDao);
        this.active = new ActiveContexts(plugin);
        this.syncManager = new OBUSyncManager(packetSender, contextManager, active, clients);
        this.delivery = new ContextDelivery(plugin, packetSender, contextManager, obuDao, clients, active, syncManager);
        this.dataTransfer = new OBUDataTransfer(plugin, obuDao, contextManager, syncManager);
        registerService(OBUService.class, delivery);
        HandshakeListener handshakeListener = new HandshakeListener(plugin, delivery, contextManager, syncManager, clients);
        registerListener(handshakeListener);
        registerPacketListener(handshakeListener);
        BoatLagInterceptor boatLagInterceptor = new BoatLagInterceptor(plugin, clients);
        registerListener(boatLagInterceptor);
        registerPacketListener(boatLagInterceptor);
        for (Player player : Bukkit.getOnlinePlayers()) {
            delivery.requestClientVersion(player);
            boatLagInterceptor.adoptRider(player);
        }
        this.sandboxPurger = new SandboxPurger(plugin, obuDao, delivery);
        schedulePurgerSweep();

        registerListener(new BoatSyncListener(plugin, syncManager));
        seedDataIfEmpty(() -> {
            Boolean hasContexts = dao.hasAnyContexts();
            return hasContexts == null ? null : !hasContexts;
        });
    }

    @Override
    public CommandNode buildCommands() {
        CommandNode obuRootNode = CommandNode.literal("wakeobu")
                .withPreset(PermissionPreset.BUILDER)
                .withGate((source, target) -> OBUCommandHelper.requireClient(plugin, source, target))
                .aliases("wobu", "wo")
                .addSubcommand(HelpCommand.getNode(plugin))
                .addSubcommand(StatusCommand.getNode(plugin))
                .addSubcommand(DefaultsCommand.getNode(plugin))
                .addSubcommand(ContextCommand.getNode(plugin))
                .addSubcommand(SandboxCommand.getNode(plugin))
                .addSubcommand(ClearCommand.getNode(plugin))
                .addSubcommand(ConfigCommand.getNode(plugin));
        for (CommandNode settingNode : SettingsCommand.getNodes(plugin)) {
            obuRootNode.addSubcommand(settingNode);
        }
        return obuRootNode;
    }

    @Override
    protected void onModuleDisable() {
        sandboxPurger = null;
        if (delivery != null && syncManager != null && packetSender != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                delivery.saveSelection(player);
                delivery.restoreGlobals(player);
                packetSender.sendWipePlayer(player);
            }
            syncManager.wipeAllBoatContexts();
            for (Player player : Bukkit.getOnlinePlayers()) {
                delivery.cleanupPlayer(player);
            }
        }
        dataTransfer = null;
        delivery = null;
        syncManager = null;
        packetSender = null;
        active = null;
        clients = null;
        contextManager = null;
        obuDao = null;
    }

    @Override
    public void reload() {
        OBUContextManager manager = this.contextManager;
        ContextDelivery service = this.delivery;
        if (manager == null || service == null) return;
        schedulePurgerSweep();
        service.pushGlobals();
        manager.reloadAsync(changedContexts -> {
            if (!service.isStale()) {
                service.resyncAffected(changedContexts);
            }
        });
    }

    public @Nullable OBUContextManager getContextManager() {
        return contextManager;
    }

    public @Nullable ContextDelivery getDelivery() {
        return delivery;
    }

    public @Nullable OBUSyncManager getSyncManager() {
        return syncManager;
    }

    public @Nullable ActiveContexts getActiveContexts() {
        return active;
    }

    public @Nullable ClientRegistry getClients() {
        return clients;
    }

    public void schedulePurgerSweep() {
        if (sandboxPurger == null) return;
        BukkitTask task = sandboxPurger.restart();
        if (task != null) registerTask(task);
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        OBUDataTransfer transfer = dataTransfer();
        return exportState(yaml) + transfer.export(yaml);
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        OBUDataTransfer transfer = dataTransfer();
        int count = importState(yaml) + transfer.importFrom(yaml);
        ContextDelivery service = this.delivery;
        if (service != null) {
            service.pushGlobals();
        }
        return count;
    }

    private @NonNull OBUDataTransfer dataTransfer() throws SQLException {
        OBUDataTransfer transfer = this.dataTransfer;
        if (transfer == null) {
            throw new SQLException("The OBU module was switched off while its data was being transferred");
        }
        return transfer;
    }
}
package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.module.AbstractModule;
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
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.PacketSender;
import dev.muggel.wake.features.obu.clients.BoatLagInterceptor;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import dev.muggel.wake.features.obu.delivery.VehicleCleanupListener;
import dev.muggel.wake.features.obu.contexts.SandboxPurger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

public class OBUModule extends AbstractModule {
    private OBUDao obuDao;
    private OBUContextManager contextManager;
    private ContextDelivery delivery;
    private SandboxPurger sandboxPurger;
    private OBUDataTransfer dataTransfer;
    public OBUModule() {
        super("obu");
    }

    @Override
    protected void onModuleEnable() {
        this.obuDao = new OBUDao(getPlugin());
        obuDao.initTables();
        registerDao(obuDao);
        Boolean hasContexts = obuDao.hasAnyContexts();
        ClientRegistry clients = new ClientRegistry();
        PacketSender packetSender = new PacketSender(clients);
        this.contextManager = new OBUContextManager(obuDao);
        this.delivery = new ContextDelivery(getPlugin(), packetSender, contextManager, obuDao, clients);
        this.dataTransfer = new OBUDataTransfer(getPlugin(), obuDao, contextManager, delivery);
        Wake.getServiceRegistry().register(OBUService.class, delivery);
        HandshakeListener handshakeListener = new HandshakeListener(getPlugin(), delivery);
        registerListener(handshakeListener);
        registerPacketListener(handshakeListener);
        BoatLagInterceptor boatLagInterceptor = new BoatLagInterceptor();
        registerListener(boatLagInterceptor);
        registerPacketListener(boatLagInterceptor);
        for (Player player : Bukkit.getOnlinePlayers()) {
            delivery.requestClientVersion(player);
        }
        this.sandboxPurger = new SandboxPurger(getPlugin(), obuDao, delivery);
        schedulePurgerSweep();

        registerListener(new VehicleCleanupListener(delivery));
        seedDataIfEmpty(hasContexts == null ? null : !hasContexts, "defaults/obu_default.yml", "OBU");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        CommandNode obuRootNode = CommandNode.literal("wakeobu")
                .withModule(OBUModule.class)
                .withPreset(PermissionPreset.BUILDER)
                .withGate((source, target) -> OBUCommandHelper.requireClient(plugin, source, target))
                .withDescription("OpenBoatUtils settings and configuration")
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
        if (delivery != null) {
            PacketSender packetSender = delivery.packetSender();
            for (Player player : Bukkit.getOnlinePlayers()) {
                delivery.saveSelection(player);
                try {
                    packetSender.sendWipePlayer(player, OBUDefinition.CONTEXT_PERSONAL);
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.WARNING, "Failed to send wipe packet", e);
                }
            }
            delivery.getSyncManager().wipeAllBoatContexts();
            for (Player player : Bukkit.getOnlinePlayers()) {
                delivery.cleanupPlayer(player);
            }
        }
        Wake.getServiceRegistry().unregister(OBUService.class);
        dataTransfer = null;
        delivery = null;
        contextManager = null;
        obuDao = null;
    }

    @Override
    public void reload() {
        OBUContextManager manager = this.contextManager;
        ContextDelivery service = this.delivery;
        if (manager == null || service == null) return;
        schedulePurgerSweep();
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        manager.reloadAsync(changedContexts -> {
            if (service.isStale()) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (service.isAffectedBy(changedContexts, player)) {
                    service.resyncActiveSelection(player);
                }
            }
        });
    }

    public @Nullable OBUContextManager getContextManager() {
        return contextManager;
    }

    public @Nullable ContextDelivery getDelivery() {
        return delivery;
    }

    public void schedulePurgerSweep() {
        if (sandboxPurger == null) return;
        BukkitTask task = sandboxPurger.restart();
        if (task != null) registerTask(task);
    }

    @Override
    @SuppressWarnings("RedundantThrows")
    protected int onExportData(YamlConfiguration yaml) throws Exception {
        OBUDataTransfer transfer = this.dataTransfer;
        return transfer == null ? 0 : transfer.export(yaml);
    }

    @Override
    protected int onImportData(YamlConfiguration yaml) throws Exception {
        OBUDataTransfer transfer = this.dataTransfer;
        return transfer == null ? 0 : transfer.importFrom(yaml);
    }
}
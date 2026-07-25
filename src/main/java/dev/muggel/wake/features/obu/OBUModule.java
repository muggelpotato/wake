package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.commands.ClearCommand;
import dev.muggel.wake.features.obu.commands.ConfigCommand;
import dev.muggel.wake.features.obu.commands.ContextCommand;
import dev.muggel.wake.features.obu.commands.DefaultsCommand;
import dev.muggel.wake.features.obu.commands.HelpCommand;
import dev.muggel.wake.features.obu.commands.SettingsCommand;
import dev.muggel.wake.features.obu.commands.StatusCommand;
import dev.muggel.wake.features.obu.commands.sandbox.SandboxCommand;
import dev.muggel.wake.features.obu.networking.HandshakeListener;
import dev.muggel.wake.features.obu.networking.PacketSender;
import dev.muggel.wake.features.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import dev.muggel.wake.features.obu.service.SandboxPurger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Boat;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Level;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

public class OBUModule extends AbstractModule {
    private OBUDao obuDao;
    private OBUContextManager contextManager;
    private OBUServiceImpl obuService;
    private SandboxPurger sandboxPurger;
    public OBUModule() {
        super("obu");
    }

    @Override
    protected void onModuleEnable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_SETTINGS);
        Bukkit.getMessenger().registerOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_CONTEXT);
        Bukkit.getMessenger().registerOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_CONFIGURATION);
        this.obuDao = new OBUDao(getPlugin());
        obuDao.initTables();
        registerDao(obuDao);
        boolean wasEmpty = !obuDao.hasAnyContexts();
        PacketSender packetSender = new PacketSender();
        this.contextManager = new OBUContextManager(obuDao);
        this.obuService = new OBUServiceImpl(getPlugin(), packetSender, contextManager, obuDao);
        Wake.getServiceRegistry().register(OBUService.class, obuService);
        HandshakeListener handshakeListener = new HandshakeListener(getPlugin(), obuService);
        registerListener(handshakeListener);
        registerPacketListener(handshakeListener);
        BoatLagInterceptor boatLagInterceptor = new BoatLagInterceptor();
        registerListener(boatLagInterceptor);
        registerPacketListener(boatLagInterceptor);
        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.applyDefaultContext(player);
        }
        this.sandboxPurger = new SandboxPurger(getPlugin(), obuDao, obuService);
        schedulePurgerSweep();

        registerListener(new Listener() {
            @EventHandler
            public void onEntityRemove(EntityRemoveEvent e) {
                if (e.getEntity() instanceof Boat) {
                    obuService.cleanupVehicle(e.getEntity().getUniqueId());
                }
            }
        });
        seedDataIfEmpty(wasEmpty, "defaults/obu_default.yml", "OBU");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        CommandNode obuRootNode = CommandNode.literal("wakeobu")
                .withModule(OBUModule.class)
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
        if (obuService != null) {
            PacketSender packetSender = new PacketSender();
            for (Player player : Bukkit.getOnlinePlayers()) {
                obuService.cleanupPlayer(player);
                try {
                    packetSender.sendWipePlayer(player, OBUDefinition.CONTEXT_PERSONAL);
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.WARNING, "Failed to send wipe packet", e);
                }
            }
            try {
                for (UUID boatId : obuService.getSyncManager().getKnownBoatContexts()) {
                    var emptyPacket = packetSender.createEntityContextPacket(boatId, Collections.emptyList());
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        packetSender.sendPrecompiledPacket(player, emptyPacket);
                    }
                }
            } catch (Exception e) {
                getPlugin().getLogger().log(Level.WARNING, "Failed to wipe boat contexts", e);
            }
        }
        Wake.getServiceRegistry().unregister(OBUService.class);
        if (getPlugin().isEnabled()) {
            Bukkit.getScheduler().runTask(getPlugin(), () -> {
                if (Wake.getServiceRegistry().get(OBUService.class) == null) {
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_SETTINGS);
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_CONTEXT);
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(getPlugin(), OBUDefinition.CHANNEL_CONFIGURATION);
                }
            });
        }
        obuService = null;
        contextManager = null;
        obuDao = null;
    }

    @Override
    public void reload() {
        OBUContextManager manager = this.contextManager;
        OBUServiceImpl service = this.obuService;
        if (manager == null || service == null) return;
        schedulePurgerSweep();
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
            getPlugin().getDatabaseManager().awaitWrites();
            manager.loadContexts();
            if (!getPlugin().isEnabled()) return;
            try {
                Bukkit.getScheduler().runTask(getPlugin(), () -> {
                    if (Wake.getServiceRegistry().get(OBUService.class) != service) return;
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        service.resyncActiveSelection(player);
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // nothing left to resync
            }
        });
    }

    public @Nullable OBUContextManager getContextManager() {
        return contextManager;
    }

    public @Nullable OBUServiceImpl getObuService() {
        return obuService;
    }

    public void schedulePurgerSweep() {
        if (sandboxPurger == null) return;
        BukkitTask task = sandboxPurger.restart();
        if (task != null) registerTask(task);
    }

    @Override
    @SuppressWarnings("RedundantThrows")
    protected int onExportData(YamlConfiguration yaml) throws Exception {
        int count = 0;
        for (String name : contextManager.getContextNames()) {
            if (name.equals(OBUDefinition.CONTEXT_EMPTY) || name.equals(OBUDefinition.CONTEXT_PERSONAL)) continue;
            OBUContext context = contextManager.getContext(name);
            if (context == null) continue;
            String path = "contexts." + name;
            yaml.set(path + ".type", context.type().name());
            if (context.ownerUuid() != null) {
                yaml.set(path + ".owner_uuid", context.ownerUuid().toString());
            }
            Map<String, List<String>> invocationsByName = new LinkedHashMap<>();
            for (OBUSetting setting : context.settings()) {
                invocationsByName.computeIfAbsent(setting.definition().name(), k -> new ArrayList<>())
                        .add(String.join(" ", setting.args()));
            }
            invocationsByName.forEach((settingName, invocations) ->
                    yaml.set(path + ".settings." + settingName,
                            invocations.size() == 1 ? invocations.getFirst() : invocations));
            count++;
        }

        boolean persistentStates = getPlugin().getStateDao().get(ConfigCommand.STATE_KEY_PERSISTENT_STATES, true);
        yaml.set("config.persistent_player_states", persistentStates);
        count++;
        String keepUnused = getPlugin().getStateDao().get(SandboxPurger.STATE_KEY_KEEP_UNUSED, SandboxPurger.DEFAULT_KEEP);
        yaml.set("config.keep_unused_sandboxes", keepUnused);
        count++;
        return count;
    }

    @Override
    protected int onImportData(YamlConfiguration yaml) throws Exception {
        ConfigurationSection contextsSec = yaml.getConfigurationSection("contexts");
        int count = 0;
        if (contextsSec != null) {
            for (String name : contextsSec.getKeys(false)) {
                String typeStr = contextsSec.getString(name + ".type", "SERVER");
                OBUContext.ContextType type;
                try {
                    type = OBUContext.ContextType.valueOf(typeStr.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    getPlugin().getLogger().warning("Skipped OBU context '" + name + "': invalid type '" + typeStr + "'");
                    continue;
                }
                String ownerStr = contextsSec.getString(name + ".owner_uuid");
                if (ownerStr != null) {
                    try {
                        UUID.fromString(ownerStr);
                    } catch (IllegalArgumentException e) {
                        getPlugin().getLogger().warning("Skipped OBU context '" + name + "': invalid owner_uuid '" + ownerStr + "'");
                        continue;
                    }
                }
                List<OBUSetting> settingsToImport = new ArrayList<>();
                ConfigurationSection settingsSec = contextsSec.getConfigurationSection(name + ".settings");
                if (settingsSec != null) {
                    for (String settingName : settingsSec.getKeys(false)) {
                        OBUDefinition def = OBUDefinition.get(settingName);
                        if (def == null) continue;
                        List<String> invocations = settingsSec.isList(settingName)
                                ? settingsSec.getStringList(settingName)
                                : List.of(String.valueOf(settingsSec.get(settingName)));
                        for (String invocation : invocations) {
                            settingsToImport.add(new OBUSetting(def, def.splitInvocation(invocation)));
                        }
                    }
                }
                obuDao.importContextData(name, type, ownerStr, settingsToImport);
                count++;
            }
        }
        ConfigurationSection configSec = yaml.getConfigurationSection("config");
        if (configSec != null) {
            for (String key : configSec.getKeys(false)) {
                Object val = configSec.get(key);
                if (val != null) {
                    try {
                        getPlugin().getStateDao().importValue("obu." + key, val);
                        count++;
                    } catch (Exception e) {
                        getPlugin().getLogger().warning("Failed to import OBU config state " + key);
                    }
                }
            }
        }
        contextManager.loadContexts();
        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.getSyncManager().syncPlayer(player);
        }
        return count;
    }
}
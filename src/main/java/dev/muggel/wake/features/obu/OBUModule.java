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
import dev.muggel.wake.features.obu.networking.HandshakeListener;
import dev.muggel.wake.features.obu.networking.PacketSender;
import dev.muggel.wake.features.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.features.obu.service.ClientRegistry;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import dev.muggel.wake.features.obu.service.SandboxPurger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Boat;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityRemoveEvent;
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;

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
        this.obuDao = new OBUDao(getPlugin());
        obuDao.initTables();
        registerDao(obuDao);
        Boolean hasContexts = obuDao.hasAnyContexts();
        ClientRegistry clients = new ClientRegistry();
        PacketSender packetSender = new PacketSender(clients);
        this.contextManager = new OBUContextManager(obuDao);
        this.obuService = new OBUServiceImpl(getPlugin(), packetSender, contextManager, obuDao, clients);
        Wake.getServiceRegistry().register(OBUService.class, obuService);
        HandshakeListener handshakeListener = new HandshakeListener(getPlugin(), obuService);
        registerListener(handshakeListener);
        registerPacketListener(handshakeListener);
        BoatLagInterceptor boatLagInterceptor = new BoatLagInterceptor();
        registerListener(boatLagInterceptor);
        registerPacketListener(boatLagInterceptor);
        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.requestClientVersion(player);
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
        seedDataIfEmpty(hasContexts == null ? null : !hasContexts, "defaults/obu_default.yml", "OBU");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        CommandNode obuRootNode = CommandNode.literal("wakeobu")
                .withModule(OBUModule.class)
                .withPresetBranch(PermissionPreset.ADMIN)
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
        if (obuService != null) {
            PacketSender packetSender = obuService.packetSender();
            for (Player player : Bukkit.getOnlinePlayers()) {
                obuService.saveSelection(player);
                try {
                    packetSender.sendWipePlayer(player, OBUDefinition.CONTEXT_PERSONAL);
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.WARNING, "Failed to send wipe packet", e);
                }
            }
            for (UUID boatId : obuService.getSyncManager().getKnownBoatContexts()) {
                try {
                    var emptyPacket = packetSender.createEntityContextPacket(boatId, Collections.emptyList());
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        packetSender.sendPrecompiledPacket(player, emptyPacket);
                    }
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.WARNING, "Failed to wipe context for boat " + boatId, e);
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                obuService.cleanupPlayer(player);
            }
        }
        Wake.getServiceRegistry().unregister(OBUService.class);
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
        manager.reloadAsync(changedContexts -> {
            if (Wake.getServiceRegistry().get(OBUService.class) != service) return;
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
        OBUContextManager manager = this.contextManager;
        if (manager == null) {
            return 0;
        }
        int count = 0;
        for (String name : manager.getContextNames()) {
            if (OBUContextManager.isInternal(name)) continue;
            OBUContext context = manager.getContext(name);
            if (context == null) continue;
            String path = sectionOf(context.type()) + "." + name;
            yaml.createSection(path);
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

        boolean persistentStates = getPlugin().getStateDao().get(OBUServiceImpl.STATE_KEY_PERSISTENT_STATES, true);
        yaml.set("config.persistent_player_states", persistentStates);
        count++;
        String keepUnused = getPlugin().getStateDao().get(SandboxPurger.STATE_KEY_KEEP_UNUSED, SandboxPurger.DEFAULT_KEEP);
        yaml.set("config.keep_unused_sandboxes", keepUnused);
        count++;
        return count;
    }

    @Override
    protected int onImportData(YamlConfiguration yaml) throws Exception {
        int count = 0;
        for (OBUContext.ContextType type : OBUContext.ContextType.values()) {
            count += importContexts(yaml.getConfigurationSection(sectionOf(type)), type);
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

    private static @NonNull String sectionOf(OBUContext.@NonNull ContextType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private int importContexts(@Nullable ConfigurationSection section, OBUContext.ContextType type) throws SQLException {
        if (section == null) {
            return 0;
        }
        int count = 0;
        for (String name : section.getKeys(false)) {
            String ownerStr = section.getString(name + ".owner_uuid");
            if (ownerStr != null) {
                try {
                    UUID.fromString(ownerStr);
                } catch (IllegalArgumentException e) {
                    getPlugin().getLogger().warning("Skipped OBU context '" + name + "': invalid owner_uuid '" + ownerStr + "'");
                    continue;
                }
            }
            List<OBUSetting> settingsToImport = new ArrayList<>();
            ConfigurationSection settingsSec = section.getConfigurationSection(name + ".settings");
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
        return count;
    }
}
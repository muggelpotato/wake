package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.commands.OBUClearCommand;
import dev.muggel.wake.features.obu.commands.OBUContextCommand;
import dev.muggel.wake.features.obu.commands.OBUDefaultsCommand;
import dev.muggel.wake.features.obu.commands.OBUHelpCommand;
import dev.muggel.wake.features.obu.commands.OBUSandboxCommand;
import dev.muggel.wake.features.obu.commands.OBUSettingsCommand;
import dev.muggel.wake.features.obu.commands.OBUStatusCommand;
import dev.muggel.wake.features.obu.networking.HandshakeListener;
import dev.muggel.wake.features.obu.networking.PacketSender;
import dev.muggel.wake.features.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.UUID;

public class OBUModule extends AbstractModule {
    private OBUContextManager contextManager;
    private OBUService obuService;

    public OBUModule() {
        super("obu");
    }

    @Override
    protected void onModuleEnable() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_SETTINGS);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_CONTEXT);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_CONFIGURATION);

        PacketSender packetSender = new PacketSender();
        this.contextManager = new OBUContextManager(plugin);
        this.obuService = new OBUServiceImpl(plugin, packetSender, contextManager);
        Wake.getServiceRegistry().register(OBUService.class, obuService);

        HandshakeListener handshakeListener = new HandshakeListener(plugin, obuService);
        registerListener(handshakeListener);
        registerPacketListener(handshakeListener);
        registerPacketListener(new BoatLagInterceptor());

        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.applyDefaultContext(player);
        }

        CommandNode obuRootNode = CommandNode.literal("wakeobu")
                .withModule(OBUModule.class)
                .withDescription("OpenBoatUtils settings and configuration")
                .aliases("wobu")
                .addSubcommand(OBUHelpCommand.getNode(plugin))
                .addSubcommand(OBUStatusCommand.getNode(plugin))
                .addSubcommand(OBUDefaultsCommand.getNode(plugin))
                .addSubcommand(OBUContextCommand.getNode(plugin))
                .addSubcommand(OBUSandboxCommand.getNode(plugin))
                .addSubcommand(OBUClearCommand.getNode(plugin));

        for (CommandNode settingNode : OBUSettingsCommand.getNodes(plugin)) {
            obuRootNode.addSubcommand(settingNode);
        }

        WakeCommandManager.register(obuRootNode);
    }

    @Override
    protected void onModuleDisable() {
        if (obuService == null) return;
        PacketSender packetSender = new PacketSender();
        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.cleanupPlayer(player);
            try {
                packetSender.sendWipePlayer(player, "wake_personal");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send wipe packet: " + e.getMessage());
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
            plugin.getLogger().warning("Failed to wipe boat contexts: " + e.getMessage());
        }

        Wake.getServiceRegistry().unregister(OBUService.class);

        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Wake.getServiceRegistry().get(OBUService.class) == null) {
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_SETTINGS);
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_CONTEXT);
                    Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, OBUDefinition.CHANNEL_CONFIGURATION);
                }
            });
        }
        WakeCommandManager.unregister("wakeobu");
    }

    @Override
    public void reload() {
        if (contextManager != null && obuService != null) {
            contextManager.loadContexts();
            for (Player player : Bukkit.getOnlinePlayers()) {
                obuService.applyDefaultContext(player);
            }
        }
    }

    public OBUContextManager getContextManager() {
        return contextManager;
    }

    public OBUService getObuService() {
        return obuService;
    }
}
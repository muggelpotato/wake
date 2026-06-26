package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.networking.HandshakeListener;
import dev.muggel.wake.features.obu.networking.PacketSender;
import dev.muggel.wake.features.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
            for (java.util.UUID boatId : obuService.getSyncManager().getKnownBoatContexts()) {
                var emptyPacket = packetSender.createEntityContextPacket(boatId, java.util.Collections.emptyList());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    packetSender.sendPrecompiledPacket(player, emptyPacket);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to wipe boat contexts: " + e.getMessage());
        }

        Wake.getServiceRegistry().unregister(OBUService.class);
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

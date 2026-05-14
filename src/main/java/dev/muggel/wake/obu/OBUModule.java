package dev.muggel.wake.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.AbstractModule;
import dev.muggel.wake.obu.commands.OBUParentCommand;
import dev.muggel.wake.obu.config.OBUProfileManager;
import dev.muggel.wake.obu.model.OBUProfile;
import dev.muggel.wake.obu.networking.HandshakeListener;
import dev.muggel.wake.obu.networking.PacketSender;
import dev.muggel.wake.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.obu.service.OBUService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class OBUModule extends AbstractModule {
    public static final String OBU_PERMISSION = "wake.obu";
    private OBUProfileManager profileManager;
    private OBUService obuService;

    public OBUModule() {
        super("obu");
    }

    @Override
    protected void onModuleEnable(Wake plugin) {
        PacketSender packetSender = new PacketSender();
        this.profileManager = new OBUProfileManager(plugin);
        this.obuService = new OBUService(plugin, packetSender);
        
        HandshakeListener handshakeListener = new HandshakeListener(plugin, profileManager, obuService);
        registerListener(plugin, handshakeListener);
        registerPacketListener(handshakeListener);

        registerPacketListener(new BoatLagInterceptor());

        registerCommand("wakeobu", new OBUParentCommand(plugin, profileManager, obuService, packetSender));

        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.applyDefaultProfile(player, profileManager);
        }
    }

    @Override
    protected void onModuleDisable(Wake plugin) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            obuService.resetPlayer(player);
        }
    }

    @Override
    public void reload(Wake plugin) {
        if (profileManager != null) {
            profileManager.loadProfiles();
            for (Player player : Bukkit.getOnlinePlayers()) {
                obuService.applyDefaultProfile(player, profileManager);
            }
        }
    }

    public OBUProfileManager getProfileManager() {
        return profileManager;
    }

    public OBUService getObuService() {
        return obuService;
    }
}

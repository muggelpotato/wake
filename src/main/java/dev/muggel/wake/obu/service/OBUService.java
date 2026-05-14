package dev.muggel.wake.obu.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.model.OBUProfile;
import dev.muggel.wake.obu.model.OBUSetting;
import dev.muggel.wake.obu.networking.PacketSender;
import org.bukkit.entity.Player;

import java.util.Collections;

public class OBUService {
    private final Wake plugin;
    private final PacketSender packetSender;

    public OBUService(Wake plugin, PacketSender packetSender) {
        this.plugin = plugin;
        this.packetSender = packetSender;
    }

    public void resetPlayer(Player player) {
        try {
            packetSender.sendDynamicPacket(player, "settings", 0, Collections.emptyList(), new String[0]);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to reset OBU settings for " + player.getName() + ": " + e.getMessage());
        }
    }

    public void applyDefaultProfile(Player player, dev.muggel.wake.obu.config.OBUProfileManager profileManager) {
        resetPlayer(player);
        OBUProfile defaultProfile = profileManager.getProfile("default");
        if (defaultProfile != null) {
            applyProfile(player, defaultProfile);
        }
    }

    public java.util.List<String> applyProfile(Player player, OBUProfile profile) {
        java.util.List<String> applied = new java.util.ArrayList<>();
        for (OBUSetting setting : profile.getSettings()) {
            if (applySetting(player, setting)) {
                applied.add(setting.definition().name());
            }
        }
        return applied;
    }

    public boolean applySetting(Player player, OBUSetting setting) {
        try {
            packetSender.sendDynamicPacket(player, 
                    setting.definition().channel(), 
                    setting.definition().id(), 
                    setting.definition().types(), 
                    setting.args());
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply OBU setting '" + setting.definition().name() + "' for " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }
}

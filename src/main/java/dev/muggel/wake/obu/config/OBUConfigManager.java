package dev.muggel.wake.obu.config;

import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.networking.PacketSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OBUConfigManager {
    private final Wake plugin;
    private final PacketSender packetSender;

    public OBUConfigManager(Wake plugin, PacketSender packetSender) {
        this.plugin = plugin;
        this.packetSender = packetSender;
    }

    public List<String> resetAndApplyProfile(Player player, String profileName) {
        try {
            packetSender.sendDynamicPacket(player, "settings", 0, Collections.emptyList(), new String[0]);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send reset packet to " + player.getName() + ": "  + e.getMessage());
        }
        if (!"default".equalsIgnoreCase(profileName)) {
            applyProfile(player, "default");
        }
        return applyProfile(player, profileName);
    }

    public List<String> applyProfile(Player player, String profileName) {
        List<String> appliedSettings = new ArrayList<>();
        ConfigurationSection profile = plugin.getConfig().getConfigurationSection("obu.profiles." + profileName);
        if (profile == null) {
            plugin.getLogger().warning("Attempted to apply unknown OBU profile: " + profileName);
            return appliedSettings;
        }

        for (String cmdName : profile.getKeys(false)) {
            ConfigurationSection cmdDef = plugin.getConfig().getConfigurationSection("obu.commands." + cmdName);

            if (cmdDef != null) {
                int id = cmdDef.getInt("id");
                String channel = cmdDef.getString("channel", "settings");
                List<String> types = cmdDef.getStringList("types");

                List<String> argList;
                if (profile.isList(cmdName)) {
                    argList = profile.getStringList(cmdName);
                } else {
                    String value = profile.getString(cmdName);
                    argList = (value != null && !value.isBlank()) ? Collections.singletonList(value) : Collections.emptyList();
                }

                String[] args = argList.toArray(new String[0]);

                if (args.length != types.size()) {
                    plugin.getLogger().warning("Profile '" + profileName + "' has incorrect argument count for '" + cmdName + "'. Expected " + types.size());
                    continue;
                }

                try {
                    packetSender.sendDynamicPacket(player, channel, id, types, args);
                    appliedSettings.add(cmdName + (args.length > 0 ? " -> " + String.join(", ", args) : ""));
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to apply setting '" + cmdName + "' in profile '" + profileName + "'. Check config types.");
                }
            } else {
                plugin.getLogger().warning("Profile '" + profileName + "' uses undefined command: '" + cmdName + "'");
            }
        }
        return appliedSettings;
    }
}
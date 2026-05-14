package dev.muggel.wake.obu.config;

import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.OBUProtocol;
import dev.muggel.wake.obu.model.OBUProfile;
import dev.muggel.wake.obu.model.OBUSetting;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OBUProfileManager {
    private final Wake plugin;
    private final Map<String, OBUProfile> profiles = new HashMap<>();

    public OBUProfileManager(Wake plugin) {
        this.plugin = plugin;
        loadProfiles();
    }

    public void loadProfiles() {
        profiles.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("obu.profiles");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection profileSection = section.getConfigurationSection(key);
            if (profileSection == null) continue;

            List<OBUSetting> settings = new ArrayList<>();
            for (String settingKey : profileSection.getKeys(false)) {
                OBUProtocol.Definition def = OBUProtocol.get(settingKey);
                if (def == null) {
                    plugin.getLogger().warning("Unknown OBU setting in profile '" + key + "': " + settingKey);
                    continue;
                }

                String[] args;
                if (profileSection.isList(settingKey)) {
                    args = profileSection.getStringList(settingKey).toArray(new String[0]);
                } else {
                    args = new String[]{profileSection.getString(settingKey)};
                }
                settings.add(new OBUSetting(def, args));
            }
            profiles.put(key.toLowerCase(), new OBUProfile(key, settings));
        }
    }

    public java.util.Set<String> getProfileNames() {
        return profiles.keySet();
    }

    public OBUProfile getProfile(String name) {
        return profiles.get(name.toLowerCase());
    }

    public Map<String, OBUProfile> getProfiles() {
        return profiles;
    }
}

package dev.muggel.wake.features.obu.service;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class OBUContextManager {
    private final Wake plugin;
    private final Map<String, OBUContext> contexts = new HashMap<>();

    public OBUContextManager(Wake plugin) {
        this.plugin = plugin;
        loadContexts();
    }

    public void loadContexts() {
        contexts.clear();
        sandboxes.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("obu.contexts");
        if (section == null) return;

        Map<String, List<OBUSetting>> tempSettings = new HashMap<>();

        for (String key : section.getKeys(false)) {
            ConfigurationSection contextSection = section.getConfigurationSection(key);
            if (contextSection == null) continue;

            List<OBUSetting> settings = new ArrayList<>();
            for (String settingKey : contextSection.getKeys(false)) {
                OBUDefinition def = OBUDefinition.get(settingKey);
                if (def == null) {
                    plugin.getLogger().warning("Unknown OBU setting in context '" + key + "': " + settingKey);
                    continue;
                }
                
                if (def.isActionSetting()) {
                    plugin.getLogger().warning("Action setting '" + settingKey + "' cannot be saved in a context! Skipping in '" + key + "'.");
                    continue;
                }

                if (contextSection.isList(settingKey)) {
                    List<?> rawList = contextSection.getList(settingKey);
                    if (rawList != null && !rawList.isEmpty()) {
                        if (rawList.getFirst() instanceof List) {
                            for (Object invocationObj : rawList) {
                                if (invocationObj instanceof List<?> invocationList) {
                                    settings.add(new OBUSetting(def, buildArgs(def, invocationList)));
                                }
                            }
                        } else if (rawList.getFirst() instanceof String && def.canRepeat() && def.types().size() > 1 && String.valueOf(rawList.getFirst()).contains(" ")) {
                            for (Object invocationObj : rawList) {
                                String invocationStr = String.valueOf(invocationObj);
                                String[] split = invocationStr.split(" ", def.types().size());
                                settings.add(new OBUSetting(def, buildArgs(def, Arrays.asList(split))));
                            }
                        } else {
                            settings.add(new OBUSetting(def, buildArgs(def, rawList)));
                        }
                    }
                } else {
                    String value = contextSection.getString(settingKey);
                    if (value == null) {
                        plugin.getLogger().warning("Invalid OBU value in context '" + key + "' for setting '" + settingKey + "'.");
                        continue;
                    }
                    settings.add(new OBUSetting(def, new String[]{value}));
                }
            }
            tempSettings.put(key.toLowerCase(Locale.ROOT), settings);
        }

        List<OBUSetting> defaultSettings = tempSettings.getOrDefault("default", Collections.emptyList());

        for (Map.Entry<String, List<OBUSetting>> entry : tempSettings.entrySet()) {
            String key = entry.getKey();
            List<OBUSetting> mergedSettings = new ArrayList<>();

            if (!key.equals("default")) {
                for (OBUSetting defSetting : defaultSettings) {
                    boolean overridden = entry.getValue().stream().anyMatch(s -> s.getUniqueKey().equals(defSetting.getUniqueKey()));
                    if (!overridden) {
                        mergedSettings.add(defSetting);
                    }
                }
            }

            mergedSettings.addAll(entry.getValue());
            contexts.put(key, new OBUContext(key, mergedSettings));
        }

        // for memory wipe without triggering RESTORE_DEFAULTS
        contexts.put("empty", new OBUContext("empty", new ArrayList<>()));
    }

    public Set<String> getContextNames() {
        return Collections.unmodifiableSet(contexts.keySet());
    }

    public OBUContext getContext(@NonNull String name) {
        return contexts.get(name.toLowerCase(Locale.ROOT));
    }

    private final Set<String> sandboxes = new HashSet<>();

    public void createSandbox(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        contexts.put(lower, new OBUContext(name, new ArrayList<>()));
        sandboxes.add(lower);
    }

    public Set<String> getSandboxNames() {
        return Collections.unmodifiableSet(sandboxes);
    }

    public void updateSandboxSetting(String name, OBUSetting setting) {
        OBUContext context = getContext(name);
        if (context == null) return;
        List<OBUSetting> settings = new ArrayList<>(context.getSettings());
        settings.removeIf(s -> s.getUniqueKey().equals(setting.getUniqueKey()));
        settings.add(setting);
        contexts.put(name.toLowerCase(Locale.ROOT), new OBUContext(context.name(), settings));
    }

    public boolean removeContextSetting(String name, int settingId) {
        OBUContext context = getContext(name);
        if (context == null) return false;
        List<OBUSetting> settings = new ArrayList<>(context.getSettings());
        boolean removed = settings.removeIf(s -> s.definition().id() == settingId);
        if (removed) {
            contexts.put(name.toLowerCase(Locale.ROOT), new OBUContext(context.name(), settings));
            return true;
        }
        return false;
    }

    private String @NonNull [] buildArgs(@NonNull OBUDefinition def, List<?> rawArgs) {
        int expected = def.types().size();
        if (expected == 0) return new String[0];
        if (rawArgs.size() < expected) {
            return rawArgs.stream().map(Object::toString).toArray(String[]::new);
        }
        String[] args = new String[expected];
        for (int i = 0; i < expected - 1; i++) {
            args[i] = String.valueOf(rawArgs.get(i));
        }
        List<String> lastArgs = new ArrayList<>();
        for (int i = expected - 1; i < rawArgs.size(); i++) {
            lastArgs.add(String.valueOf(rawArgs.get(i)));
        }
        args[expected - 1] = String.join(",", lastArgs);
        return args;
    }
}

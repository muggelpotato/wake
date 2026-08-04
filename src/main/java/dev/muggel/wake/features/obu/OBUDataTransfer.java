package dev.muggel.wake.features.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class OBUDataTransfer {
    private final Wake plugin;
    private final OBUDao obuDao;
    private final OBUContextManager contextManager;
    private final OBUSyncManager syncManager;
    OBUDataTransfer(@NonNull Wake plugin, @NonNull OBUDao obuDao, @NonNull OBUContextManager contextManager, @NonNull OBUSyncManager syncManager) {
        this.plugin = plugin;
        this.obuDao = obuDao;
        this.contextManager = contextManager;
        this.syncManager = syncManager;
    }

    int export(@NonNull YamlConfiguration yaml) throws SQLException {
        if (!contextManager.isLoaded()) {
            throw new SQLException("OBU contexts could not be read");
        }
        int count = 0;
        for (String name : contextManager.getContextNames()) {
            if (OBUContextManager.isInternal(name)) continue;
            OBUContext context = contextManager.getContext(name);
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
        return count;
    }

    int importFrom(@NonNull YamlConfiguration yaml) throws SQLException {
        int count = 0;
        for (OBUContext.ContextType type : OBUContext.ContextType.values()) {
            count += importContexts(yaml.getConfigurationSection(sectionOf(type)), type);
        }
        contextManager.loadContexts();
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncManager.syncPlayer(player);
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
                    plugin.getLogger().warning("Skipped OBU context '" + name + "': invalid owner_uuid '" + ownerStr + "'");
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
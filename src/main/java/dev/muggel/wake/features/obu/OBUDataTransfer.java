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
        for (OBUContext context : contextManager.getContexts()) {
            if (OBUContextManager.isInternal(context.name())) continue;
            String path = sectionOf(context.type()) + "." + context.name();
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
        if (count == 0) {
            return 0;
        }
        contextManager.loadContexts();
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncManager.syncPlayer(player);
        }
        syncManager.resyncPinnedBoats();
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
            UUID owner = null;
            if (ownerStr != null) {
                try {
                    owner = UUID.fromString(ownerStr);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipped OBU context '" + name + "': invalid owner_uuid '" + ownerStr + "'");
                    continue;
                }
            }
            if (OBUContextManager.isUnaddressable(name, type, owner)) {
                plugin.getLogger().warning("Skipped OBU context '" + name + "': no command could reach a context stored under that name");
                continue;
            }
            if (type == OBUContext.ContextType.SANDBOX && OBUContextManager.isReserved(OBUContextManager.displayName(name))) {
                plugin.getLogger().warning("Skipped OBU context '" + name + "': that name is reserved");
                continue;
            }
            List<OBUSetting> settingsToImport = new ArrayList<>();
            ConfigurationSection settingsSec = section.getConfigurationSection(name + ".settings");
            if (settingsSec != null) {
                for (String settingName : settingsSec.getKeys(false)) {
                    OBUDefinition def = OBUDefinition.byName(settingName);
                    if (def == null) continue;
                    if (def.isOneShot()) {
                        plugin.getLogger().warning("Skipped OBU setting '" + settingName + "' in context '" + name + "': that setting acts once, so no context holds it");
                        continue;
                    }
                    List<String> invocations = settingsSec.isList(settingName)
                            ? settingsSec.getStringList(settingName)
                            : List.of(String.valueOf(settingsSec.get(settingName)));
                    for (String invocation : invocations) {
                        OBUSetting setting = OBUSetting.of(def, def.splitInvocation(invocation));
                        if (setting == null) {
                            plugin.getLogger().warning("Skipped OBU setting '" + settingName + " " + invocation + "' in context '" + name + "': the client cannot be sent that value");
                            continue;
                        }
                        settingsToImport.add(setting);
                    }
                }
            }
            obuDao.importContextData(name, type, owner, settingsToImport);
            count++;
        }
        return count;
    }
}
package dev.muggel.wake.features.base;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.database.StateDao;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.base.commands.HelpCommand;
import dev.muggel.wake.features.base.commands.KillEmptyBoatsCommand;
import dev.muggel.wake.features.base.commands.ReloadCommand;
import dev.muggel.wake.features.base.commands.database.DatabaseCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.util.logging.Level;

import java.util.Map;

public class BaseModule extends AbstractModule {
    public static final String STATE_KEY_KILL_BOAT_ON_EXIT = "base.killboatonexit";
    public BaseModule() {
        super("base");
    }

    @Override
    protected void onModuleEnable() {
        registerListener(new EmptyBoatListener(this));
        StateDao stateDao = getPlugin().getStateDao();
        seedDataIfEmpty(stateDao.isLoaded() ? stateDao.snapshot("base.").isEmpty() : null, "defaults/base_default.yml", "Base Configs");
    }

    @Override
    public CommandNode buildCommands(Wake plugin) {
        return CommandNode.literal("wake")
                .withModule(BaseModule.class)
                .withDescription("Main command for Wake")
                .aliases("wa")
                .addSubcommand(HelpCommand.getNode(plugin))
                .addSubcommand(ReloadCommand.getNode(plugin))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "killboatonexit", STATE_KEY_KILL_BOAT_ON_EXIT, "words.feature.auto_kill")
                        .withPreset(PermissionPreset.BUILDER))
                .addSubcommand(KillEmptyBoatsCommand.getNode(plugin))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "hints", CommandHelper.STATE_KEY_SHOW_HINTS, "words.feature.hints"))
                .addSubcommand(DatabaseCommand.getNode(plugin));
    }

    @Override
    public void reload() {
        if (getPlugin().getDatabaseManager().isDegraded()) return;
        getPlugin().getStateDao().reloadAsync(null);
    }

    public boolean isKillBoatOnExit() {
        return getPlugin().getStateDao().get(STATE_KEY_KILL_BOAT_ON_EXIT, false);
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) {
        Map<String, Object> entries = getPlugin().getStateDao().snapshot("base.");
        entries.forEach(yaml::set);
        return entries.size();
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) {
        int count = 0;
        String prefix = getId() + ".";
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) continue;
            if (!key.startsWith(prefix)) continue;
            Object val = yaml.get(key);
            if (val != null) {
                try {
                    getPlugin().getStateDao().importValue(key, val);
                    count++;
                } catch (Exception e) {
                    getPlugin().getLogger().log(Level.WARNING, "Failed to import state " + key, e);
                }
            }
        }
        return count;
    }
}
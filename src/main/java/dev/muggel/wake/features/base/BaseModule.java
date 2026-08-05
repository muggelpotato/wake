package dev.muggel.wake.features.base;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.database.StateDao;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.features.base.commands.HelpCommand;
import dev.muggel.wake.features.base.commands.KillEmptyBoatsCommand;
import dev.muggel.wake.features.base.commands.ReloadCommand;
import dev.muggel.wake.features.base.commands.database.DatabaseCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NonNull;

import java.sql.SQLException;

public class BaseModule extends WakeModule {
    public BaseModule(Wake plugin) {
        super(plugin, "base");
    }

    @Override
    protected void onModuleEnable() {
        registerListener(new EmptyBoatListener(plugin));
        StateDao stateDao = plugin.getStateDao();
        seedDataIfEmpty(() -> stateDao.isLoaded() ? stateDao.snapshot(statePrefix).isEmpty() : null);
    }

    @Override
    public CommandNode buildCommands() {
        return CommandNode.literal("wake")
                .aliases("wa")
                .addSubcommand(HelpCommand.getNode(plugin))
                .addSubcommand(ReloadCommand.getNode(plugin))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "killboatonexit", EmptyBoatListener.STATE_KEY_KILL_BOAT_ON_EXIT, "words.feature.auto_kill")
                        .withPreset(PermissionPreset.BUILDER))
                .addSubcommand(KillEmptyBoatsCommand.getNode(plugin))
                .addSubcommand(CommandHelper.toggleCommand(plugin, "hints", CommandHelper.STATE_KEY_SHOW_HINTS, "words.feature.hints"))
                .addSubcommand(DatabaseCommand.getNode(plugin));
    }

    @Override
    protected int onExportData(@NonNull YamlConfiguration yaml) throws SQLException {
        return exportState(yaml);
    }

    @Override
    protected int onImportData(@NonNull YamlConfiguration yaml) throws SQLException {
        return importState(yaml);
    }
}
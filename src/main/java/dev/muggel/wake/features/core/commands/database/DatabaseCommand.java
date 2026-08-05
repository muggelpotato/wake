package dev.muggel.wake.features.core.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

public class DatabaseCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("database")
                .addSubcommand(DatabaseExportCommand.getNode(plugin))
                .addSubcommand(DatabaseImportCommand.getNode(plugin))
                .addSubcommand(DatabaseDropCommand.getNode(plugin))
                .addSubcommand(DatabaseSetDefaultsCommand.getNode(plugin));
    }
}
package dev.muggel.wake.features.core.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.nio.file.Files;

class DatabaseExportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("export")
                .arguments(DatabaseCommandHelper.moduleArgument(plugin)
                        .executesSender((ctx, sender) -> DatabaseCommandHelper.runExport(plugin, ctx,
                                module -> {
                                    File exportDir = DatabaseCommandHelper.exportDir(plugin);
                                    Files.createDirectories(exportDir.toPath());
                                    return module.exportData(exportDir);
                                })));
    }
}
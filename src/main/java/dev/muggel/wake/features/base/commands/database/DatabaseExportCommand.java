package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.nio.file.Files;

public class DatabaseExportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("export")
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(DatabaseCommandHelper.moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> DatabaseCommandHelper.runExport(plugin, ctx,
                                module -> {
                                    File exportDir = new File(plugin.getDataFolder(), "exports");
                                    Files.createDirectories(exportDir.toPath());
                                    return module.exportData(exportDir);
                                })));
    }
}
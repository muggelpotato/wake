package dev.muggel.wake.features.base.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.FileNotFoundException;

public class DatabaseImportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return DatabaseCommandHelper.confirmedNode(plugin, "import", "commands.database.import_confirm",
                "commands.database.import_success", "commands.database.import_fail",
                module -> {
                    File importDir = new File(plugin.getDataFolder(), "exports");
                    if (!importDir.exists()) {
                        throw new FileNotFoundException(importDir.getPath());
                    }
                    return module.importData(importDir);
                });
    }
}
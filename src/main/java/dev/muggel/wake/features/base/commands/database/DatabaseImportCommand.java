package dev.muggel.wake.features.base.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

class DatabaseImportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return DatabaseCommandHelper.confirmedNode(plugin, "import", "commands.database.import_confirm",
                "commands.database.import_success", "commands.database.import_fail",
                module -> module.importData(DatabaseCommandHelper.exportDir(plugin)));
    }
}
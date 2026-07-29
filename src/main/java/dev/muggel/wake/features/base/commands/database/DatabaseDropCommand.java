package dev.muggel.wake.features.base.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import org.jspecify.annotations.NonNull;

public class DatabaseDropCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return DatabaseCommandHelper.confirmedNode(plugin, "drop", "commands.database.drop_confirm",
                "commands.database.drop_success", "commands.database.drop_fail",
                module -> {
                    module.resetDatabase();
                    return 0;
                });
    }
}
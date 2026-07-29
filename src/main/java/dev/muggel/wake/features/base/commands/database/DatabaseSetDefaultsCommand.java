package dev.muggel.wake.features.base.commands.database;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.WakeModule;
import org.jspecify.annotations.NonNull;

public class DatabaseSetDefaultsCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return DatabaseCommandHelper.confirmedNode(plugin, "setdefaults", "commands.database.setdefaults_confirm",
                "commands.database.setdefaults_success", "commands.database.setdefaults_fail",
                WakeModule::seedData);
    }
}
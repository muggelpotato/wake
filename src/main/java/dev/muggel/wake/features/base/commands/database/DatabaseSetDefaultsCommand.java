package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NonNull;

public class DatabaseSetDefaultsCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("setdefaults")
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(DatabaseCommandHelper.moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        return DatabaseCommandHelper.runModuleOperation(plugin, ctx,
                "commands.database.setdefaults_success", "commands.database.setdefaults_fail",
                WakeModule::seedData);
    }
}
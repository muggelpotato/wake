package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NonNull;

public class DatabaseDropCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("drop")
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(DatabaseCommandHelper.moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        return DatabaseCommandHelper.runModuleOperation(plugin, ctx,
                "commands.database.drop_success", "commands.database.drop_fail",
                module -> {
                    module.resetDatabase();
                    return 0;
                });
    }
}
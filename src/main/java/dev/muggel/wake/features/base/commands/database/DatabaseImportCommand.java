package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;

import java.io.File;

public class DatabaseImportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("import")
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(DatabaseCommandHelper.moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        File importDir = new File(plugin.getDataFolder(), "exports");
        if (!importDir.exists()) {
            plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.database.import_fail",
                    Placeholder.unparsed("module", DatabaseCommandHelper.moduleId(ctx)));
            return 0;
        }
        return DatabaseCommandHelper.runModuleOperation(plugin, ctx,
                "commands.database.import_success", "commands.database.import_fail",
                module -> module.importData(importDir));
    }
}
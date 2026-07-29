package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Level;

final class DatabaseCommandHelper {
    private DatabaseCommandHelper() {}

    @FunctionalInterface
    interface ModuleOperation {
        int run(WakeModule module) throws Exception;
    }

    static @NonNull CommandNode confirmedNode(Wake plugin, String literal, String warnKey, String successKey, String failKey, ModuleOperation operation) {
        return CommandNode.literal(literal)
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> {
                            warnUnconfirmed(plugin, ctx, literal, warnKey);
                            return 0;
                        })
                        .addSubcommand(CommandNode.literal("confirm")
                                .executesSender((ctx, sender) -> runModuleOperation(plugin, ctx, literal, successKey, failKey, operation))));
    }

    private static void warnUnconfirmed(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, String literal, String warnKey) {
        CommandSender sender = ctx.getSource().getSender();
        WakeModule module = resolveModule(plugin, sender, moduleId(ctx));
        if (module == null) return;
        if (databaseUnavailable(plugin, sender)) return;
        MessageManager messages = plugin.getMessageManager();
        messages.send(sender, warnKey,
                Placeholder.unparsed("module", module.getId()),
                Placeholder.component("confirm_btn", messages.getComponent("commands.database.confirm_btn",
                        Placeholder.parsed("literal", literal),
                        Placeholder.parsed("module", module.getId()))));
    }

    static int runModuleOperation(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, String literal, String successKey, String failKey, ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        if (resolveModule(plugin, sender, moduleId) == null) return 0;
        if (databaseUnavailable(plugin, sender)) return 0;
        plugin.getDatabaseManager().runWithDrainedQueue(() -> {
            WakeModule current = resolveModule(plugin, sender, moduleId);
            if (current == null) return;
            try {
                int count = operation.run(current);
                plugin.getLogger().info("Database " + literal + " completed for module " + moduleId + " (" + count + " records)");
                plugin.getMessageManager().send(sender, successKey,
                        Placeholder.unparsed("module", moduleId),
                        Placeholder.unparsed("count", String.valueOf(count)));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Database operation failed for module " + moduleId + " (" + failKey + ")", e);
                plugin.getMessageManager().send(sender, failKey, Placeholder.unparsed("module", moduleId));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    static @NonNull SuggestionProvider<CommandSourceStack> moduleSuggester(Wake plugin) {
        return (ctx, builder) -> CommandHelper.suggestMatching(builder,
                plugin.getLoadedModules().stream().map(WakeModule::getId).toList());
    }

    static @Nullable WakeModule resolveModule(@NonNull Wake plugin, CommandSender sender, String moduleId) {
        for (WakeModule m : plugin.getLoadedModules()) {
            if (m.getId().equals(moduleId)) {
                return m;
            }
        }
        plugin.getMessageManager().send(sender, "commands.base.module_not_loaded", Placeholder.unparsed("module", moduleId));
        return null;
    }

    static @NonNull String moduleId(@NonNull CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "module").toLowerCase(Locale.ROOT);
    }

    static boolean databaseUnavailable(@NonNull Wake plugin, CommandSender sender) {
        if (plugin.getDatabaseManager().isDegraded()) {
            plugin.getMessageManager().send(sender, "commands.database.unavailable");
            return true;
        }
        return false;
    }
}
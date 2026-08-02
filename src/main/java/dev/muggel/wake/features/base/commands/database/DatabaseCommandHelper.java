package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;
import java.util.logging.Level;

final class DatabaseCommandHelper {
    private DatabaseCommandHelper() {}

    @FunctionalInterface
    interface ModuleOperation {
        int run(WakeModule module) throws SQLException, IOException;
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
                                .executesSender((ctx, sender) -> runOnMain(plugin, ctx, literal, successKey, failKey, operation))));
    }

    private static void warnUnconfirmed(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, String literal, String warnKey) {
        CommandSender sender = ctx.getSource().getSender();
        WakeModule module = operableModule(plugin, sender, moduleId(ctx));
        if (module == null) return;
        MessageManager messages = plugin.getMessageManager();
        messages.send(sender, warnKey,
                Placeholder.unparsed("module", module.getId()),
                Placeholder.component("confirm_btn", messages.getComponent("commands.database.confirm_btn",
                        Placeholder.parsed("literal", literal),
                        Placeholder.parsed("module", module.getId()))));
    }

    static int runOnMain(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, String literal, String successKey, String failKey, ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        if (operableModule(plugin, sender, moduleId) == null) return 0;
        plugin.getDatabaseManager().runWithDrainedQueue(() -> {
            WakeModule current = resolveModule(plugin, sender, moduleId);
            if (current != null) {
                report(plugin, sender, literal, moduleId, successKey, failKey, current, operation);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    static int runExport(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        WakeModule module = operableModule(plugin, sender, moduleId);
        if (module == null) return 0;
        Scheduling.async(plugin, () -> report(plugin, sender, "export", moduleId, "commands.database.export_success", "commands.database.export_fail", module, operation));
        return Command.SINGLE_SUCCESS;
    }

    private static void report(Wake plugin, CommandSender sender, String literal, String moduleId, String successKey, String failKey, WakeModule module, @NonNull ModuleOperation operation) {
        String key;
        int count = 0;
        try {
            count = operation.run(module);
            plugin.getLogger().info("Database " + literal + " completed for module " + moduleId + " (" + count + " records)");
            key = successKey;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Database " + literal + " failed for module " + moduleId, e);
            key = failKey;
        }
        int reported = count;
        String messageKey = key;
        Scheduling.onMain(plugin, () -> plugin.getMessageManager().send(sender, messageKey,
                Placeholder.unparsed("module", moduleId),
                Placeholder.unparsed("count", String.valueOf(reported))));
    }

    private static @Nullable WakeModule operableModule(@NonNull Wake plugin, CommandSender sender, String moduleId) {
        WakeModule module = resolveModule(plugin, sender, moduleId);
        return module != null && !databaseUnavailable(plugin, sender) ? module : null;
    }

    static @NonNull SuggestionProvider<CommandSourceStack> moduleSuggester(Wake plugin) {
        return (ctx, builder) -> CommandHelper.suggestMatching(builder,
                plugin.getLoadedModules().stream().map(WakeModule::getId).toList());
    }

    private static @Nullable WakeModule resolveModule(@NonNull Wake plugin, CommandSender sender, String moduleId) {
        for (WakeModule m : plugin.getLoadedModules()) {
            if (m.getId().equals(moduleId)) {
                return m;
            }
        }
        plugin.getMessageManager().send(sender, "commands.base.module_not_loaded", Placeholder.unparsed("module", moduleId));
        return null;
    }

    private static @NonNull String moduleId(@NonNull CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "module").toLowerCase(Locale.ROOT);
    }

    private static boolean databaseUnavailable(@NonNull Wake plugin, CommandSender sender) {
        if (plugin.getDatabaseManager().isDegraded()) {
            plugin.getMessageManager().send(sender, "commands.database.unavailable");
            return true;
        }
        return false;
    }
}
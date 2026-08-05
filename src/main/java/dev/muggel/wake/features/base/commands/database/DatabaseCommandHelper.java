package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.ModuleArgumentType;
import dev.muggel.wake.core.database.DatabaseManager;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class DatabaseCommandHelper {
    private static final Set<String> BUSY_MODULES = ConcurrentHashMap.newKeySet();
    private DatabaseCommandHelper() {}

    @FunctionalInterface
    interface ModuleOperation {
        int run(@NonNull WakeModule module) throws SQLException, IOException;
    }

    static @NonNull CommandNode confirmedNode(@NonNull Wake plugin, @NonNull String literal, @NonNull String warnKey, @NonNull String successKey, @NonNull String failKey, @NonNull ModuleOperation operation) {
        return CommandNode.literal(literal)
                .arguments(moduleArgument(plugin)
                        .executesSender((ctx, sender) -> {
                            warnUnconfirmed(plugin, ctx, literal, warnKey);
                            return 0;
                        })
                        .addSubcommand(CommandNode.literal("confirm")
                                .executesSender((ctx, sender) -> runDrained(plugin, ctx, literal, successKey, failKey, operation))));
    }

    static @NonNull CommandNode moduleArgument(@NonNull Wake plugin) {
        return CommandNode.argument("module", ModuleArgumentType.of(plugin));
    }

    static @NonNull File exportDir(@NonNull Wake plugin) {
        return new File(plugin.getDataFolder(), "exports");
    }

    static int runExport(@NonNull Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, @NonNull ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        if (databaseUnavailable(plugin, sender)) {
            return 0;
        }
        WakeModule module = resolveModule(plugin, sender, moduleId);
        if (module == null || moduleBusy(plugin, sender, moduleId)) {
            return 0;
        }
        Scheduling.async(plugin, () -> {
            try {
                report(plugin, sender, "export", module,
                        "commands.database.export_success", "commands.database.export_fail", operation);
            } finally {
                BUSY_MODULES.remove(moduleId);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int runDrained(@NonNull Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, @NonNull String literal, @NonNull String successKey, @NonNull String failKey, @NonNull ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        if (databaseUnavailable(plugin, sender) || moduleBusy(plugin, sender, moduleId)) {
            return 0;
        }
        plugin.getDatabaseManager().runWithDrainedQueue(() -> {
            try {
                WakeModule module = resolveModule(plugin, sender, moduleId);
                if (module != null) {
                    attributedTo(plugin, sender, () -> report(plugin, sender, literal, module, successKey, failKey, operation));
                }
            } finally {
                BUSY_MODULES.remove(moduleId);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static boolean moduleBusy(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String moduleId) {
        if (BUSY_MODULES.add(moduleId)) {
            return false;
        }
        plugin.getMessageManager().send(sender, "commands.database.busy", Placeholder.unparsed("module", moduleId));
        return true;
    }

    private static void warnUnconfirmed(@NonNull Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, @NonNull String literal, @NonNull String warnKey) {
        CommandSender sender = ctx.getSource().getSender();
        if (databaseUnavailable(plugin, sender)) {
            return;
        }
        String moduleId = moduleId(ctx);
        MessageManager messages = plugin.getMessageManager();
        messages.send(sender, warnKey,
                Placeholder.unparsed("module", moduleId),
                Placeholder.component("confirm_btn", messages.getComponent("commands.database.confirm_btn",
                        Placeholder.parsed("literal", literal),
                        Placeholder.parsed("module", moduleId))));
    }

    private static void report(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String literal, @NonNull WakeModule module, @NonNull String successKey, @NonNull String failKey, @NonNull ModuleOperation operation) {
        String moduleId = module.getId();
        try {
            int count = operation.run(module);
            plugin.getLogger().info("Database " + literal + " completed for module " + moduleId + " (" + count + " records)");
            answer(plugin, sender, successKey, moduleId, String.valueOf(count), "");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Database " + literal + " failed for module " + moduleId, e);
            answer(plugin, sender, failKey, moduleId, "0", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static void answer(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String messageKey, @NonNull String moduleId, @NonNull String count, @NonNull String error) {
        Scheduling.onMain(plugin, () -> plugin.getMessageManager().send(sender, messageKey,
                Placeholder.unparsed("module", moduleId),
                Placeholder.unparsed("count", count),
                Placeholder.unparsed("error", error)));
    }

    private static void attributedTo(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull Runnable body) {
        DatabaseManager database = plugin.getDatabaseManager();
        database.setActor(sender);
        try {
            body.run();
        } finally {
            database.restoreActor(null);
        }
    }

    private static @Nullable WakeModule resolveModule(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull String moduleId) {
        for (WakeModule module : plugin.getActiveModules()) {
            if (module.getId().equals(moduleId)) {
                return module;
            }
        }
        plugin.getMessageManager().send(sender, "commands.base.module_not_loaded", Placeholder.unparsed("module", moduleId));
        return null;
    }

    private static @NonNull String moduleId(@NonNull CommandContext<CommandSourceStack> ctx) {
        return ctx.getArgument("module", String.class);
    }

    private static boolean databaseUnavailable(@NonNull Wake plugin, @NonNull CommandSender sender) {
        if (plugin.getDatabaseManager().isDegraded()) {
            plugin.getMessageManager().send(sender, "commands.database.unavailable");
            return true;
        }
        return false;
    }
}
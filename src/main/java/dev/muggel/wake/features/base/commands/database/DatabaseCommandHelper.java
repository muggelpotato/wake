package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
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

    static int runModuleOperation(Wake plugin, @NonNull CommandContext<CommandSourceStack> ctx, String successKey, String failKey, ModuleOperation operation) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = moduleId(ctx);
        if (resolveModule(plugin, sender, moduleId) == null) return 0;
        if (databaseUnavailable(plugin, sender)) return 0;
        runWithDrainedQueue(plugin, () -> {
            WakeModule current = resolveModule(plugin, sender, moduleId);
            if (current == null) return;
            try {
                int count = operation.run(current);
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

    static void runWithDrainedQueue(Wake plugin, Runnable mainThreadBody) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().awaitWrites();
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, mainThreadBody);
            } catch (IllegalPluginAccessException ignored) {
                // plugin disabled between check and schedule
            }
        });
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
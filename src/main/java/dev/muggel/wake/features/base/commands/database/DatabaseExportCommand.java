package dev.muggel.wake.features.base.commands.database;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.logging.Level;

public class DatabaseExportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("export")
                .arguments(CommandNode.argument("module", StringArgumentType.string())
                        .suggests(DatabaseCommandHelper.moduleSuggester(plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String moduleId = DatabaseCommandHelper.moduleId(ctx);
        WakeModule module = DatabaseCommandHelper.resolveModule(plugin, sender, moduleId);
        if (module == null) return 0;
        if (DatabaseCommandHelper.databaseUnavailable(plugin, sender)) return 0;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File exportDir = new File(plugin.getDataFolder(), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                plugin.getLogger().severe("Failed to create export directory for module " + moduleId);
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getMessageManager().send(sender, "commands.database.export_fail", Placeholder.unparsed("module", moduleId)));
                return;
            }
            try {
                int exported = module.exportData(exportDir);
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getMessageManager().send(sender, "commands.database.export_success",
                                Placeholder.unparsed("module", moduleId),
                                Placeholder.unparsed("count", String.valueOf(exported))));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to export data for module " + moduleId, e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getMessageManager().send(sender, "commands.database.export_fail", Placeholder.unparsed("module", moduleId)));
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
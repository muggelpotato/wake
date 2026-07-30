package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.service.OBUContextManager.ContextCounts;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import dev.muggel.wake.features.obu.service.SandboxPurger;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.Locale;

public class ConfigCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-settings")
                .withHelpKey("commands.obu.help.config")
                .withoutPresets()
                .withGate(CommandNode.Gate.OPEN)
                .addSubcommand(CommandHelper.toggleCommand(plugin, "persistence", OBUServiceImpl.STATE_KEY_PERSISTENT_STATES, "words.feature.persistent_states"))
                .addSubcommand(CommandNode.literal("keep-unused-sandboxes")
                        .arguments(CommandNode.argument("duration", StringArgumentType.string())
                                .executesSender((ctx, sender) -> executeKeepUnused(ctx, plugin))))
                .addSubcommand(CommandNode.literal("query-context-quantity")
                        .executesSender((ctx, sender) -> executeContextQuantity(ctx, plugin)));
    }

    private static int executeContextQuantity(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        ContextCounts counts = OBUCommandHelper.contexts(plugin).countContexts();
        if (counts == null) {
            plugin.getMessageManager().send(sender, "commands.obu.config.context_unavailable");
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.config.context_quantity",
                Placeholder.unparsed("server", String.valueOf(counts.serverContexts())),
                Placeholder.unparsed("sandboxes", String.valueOf(counts.sandboxes())),
                Placeholder.unparsed("total", String.valueOf(counts.total())));
        return Command.SINGLE_SUCCESS;
    }

    private static int executeKeepUnused(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String raw = StringArgumentType.getString(ctx, "duration");
        long millis = SandboxPurger.parseKeepMillis(raw);
        if (millis < 0) {
            plugin.getMessageManager().send(sender, "commands.obu.config.invalid_duration", Placeholder.unparsed("input", raw));
            return 0;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        plugin.getStateDao().set(SandboxPurger.STATE_KEY_KEEP_UNUSED, normalized);
        OBUCommandHelper.module(plugin).schedulePurgerSweep();
        if (millis == 0) {
            plugin.getMessageManager().send(sender, "commands.obu.config.purge_disabled");
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.config.purge_set", Placeholder.unparsed("duration", normalized));
        }
        return Command.SINGLE_SUCCESS;
    }
}
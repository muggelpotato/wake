package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class SandboxViewCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("view")
                .withGate(CommandNode.Gate.OPEN)
                .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = SandboxCommandHelper.requireOwnSandbox(plugin, sender, subject, name);
        if (context == null) {
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.header", Placeholder.unparsed("sandbox", OBUContextManager.displayName(context.name())));
        if (context.settings().isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.no_settings");
        } else {
            for (OBUSetting setting : context.settings()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.line",
                        Placeholder.parsed("name", setting.definition().name()),
                        Placeholder.unparsed("value", String.join(", ", OBUCommandHelper.displayArgs(setting))));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
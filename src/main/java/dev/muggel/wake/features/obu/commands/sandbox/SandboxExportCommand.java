package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.StringJoiner;

public class SandboxExportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("export")
                .withGate(CommandNode.Gate.OPEN)
                .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) throws IOException {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = SandboxCommandHelper.requireOwnSandbox(plugin, sender, subject, name);
        if (context == null) {
            return 0;
        }
        StringJoiner joiner = new StringJoiner(";");
        for (OBUSetting setting : context.settings()) {
            joiner.add(setting.definition().id() + ":" + String.join(" ", setting.args()));
        }
        MessageManager messages = plugin.getMessageManager();
        messages.send(sender, "commands.obu.sandbox.header_export",
                Placeholder.unparsed("sandbox", OBUContextManager.displayName(context.name())),
                Placeholder.component("button", messages.getComponent("commands.obu.sandbox.code",
                        Placeholder.parsed("code", SandboxCommandHelper.encodeShareCode(joiner.toString())))));
        return Command.SINGLE_SUCCESS;
    }
}
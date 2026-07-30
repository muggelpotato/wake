package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class SandboxPublishCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("publish")
                .withoutPresets()
                .withGate(CommandNode.Gate.OPEN)
                .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = SandboxCommandHelper.requireOwnSandbox(plugin, sender, subject, name);
        if (context == null) {
            return 0;
        }
        if (!service.publishSandbox(context.name())) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", OBUContextManager.displayName(context.name())));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.published", Placeholder.unparsed("sandbox", name));
        return Command.SINGLE_SUCCESS;
    }
}
package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxForkCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("fork")
                .arguments(
                        CommandNode.argument("contextToLoad", NameArgumentType.word())
                                .suggests((ctx, builder) -> OBUCommandHelper.suggestContexts(ctx, builder, plugin, context -> true)),
                        SandboxCommandHelper.nameArgument("newName")
                                .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String contextToLoad = StringArgumentType.getString(ctx, "contextToLoad");
        String newName = StringArgumentType.getString(ctx, "newName");
        OBUContext sourceContext = OBUCommandHelper.resolveForSubject(plugin, subject, contextToLoad);
        if (sourceContext == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", contextToLoad));
            return 0;
        }
        String newKey = SandboxCommandHelper.sandboxKeyFor(subject, newName);
        if (!service.createSandbox(newKey, SandboxCommandHelper.ownerOf(subject))) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", newName));
            return 0;
        }
        contextManager.addSettings(newKey, sourceContext.settings());
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.forked", Placeholder.unparsed("source", OBUContextManager.displayName(sourceContext.name())), Placeholder.unparsed("sandbox", newName));
        if (subject instanceof Player p) {
            SandboxCommandHelper.enterSandbox(p, newKey, service);
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.unparsed("sandbox", newName));
            SandboxCommandHelper.sendHintIfEnabled(plugin, sender);
        }
        return Command.SINGLE_SUCCESS;
    }
}
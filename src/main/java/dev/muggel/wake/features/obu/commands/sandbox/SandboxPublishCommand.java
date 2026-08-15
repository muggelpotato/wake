package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
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

import java.util.List;

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
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = SandboxCommandHelper.requireOwnSandbox(plugin, sender, subject, name);
        if (context == null) {
            return 0;
        }
        List<Player> evicted = service.publishSandbox(context.name());
        if (evicted == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", OBUContextManager.displayName(context.name())));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.published", Placeholder.unparsed("sandbox", name), CommandHelper.hint(plugin, "commands.obu.sandbox.published_hint"));
        for (Player player : evicted) {
            if (!player.equals(sender)) {
                plugin.getMessageManager().send(player, "commands.obu.sandbox.published_kicked", Placeholder.unparsed("sandbox", name));
            }
        }
        return Command.SINGLE_SUCCESS;
    }
}
package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxSwitchCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("switch")
                .arguments(CommandNode.argument("name", NameArgumentType.greedy())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesPlayer((ctx, player) -> execute(ctx, player, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        OBUContext context = SandboxCommandHelper.requireOwnSandbox(plugin, sender, player, name);
        if (context == null) {
            return 0;
        }
        SandboxCommandHelper.enterSandbox(plugin, player, context.name());
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.unparsed("sandbox", name), SandboxCommandHelper.hint(plugin, player));
        return Command.SINGLE_SUCCESS;
    }
}
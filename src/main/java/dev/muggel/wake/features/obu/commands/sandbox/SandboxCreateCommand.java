package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxCreateCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("create")
                .arguments(SandboxCommandHelper.nameArgument("name")
                        .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        String key = SandboxCommandHelper.claimSandbox(plugin, sender, subject, name);
        if (key == null) {
            return 0;
        }
        if (subject instanceof Player player) {
            SandboxCommandHelper.enterSandbox(plugin, player, key);
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.created", Placeholder.unparsed("sandbox", name));
        SandboxCommandHelper.sendHintIfEnabled(plugin, sender);
        return Command.SINGLE_SUCCESS;
    }
}
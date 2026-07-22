package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class HelpCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-help")
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager mm = plugin.getMessageManager();
        mm.send(sender, "commands.obu.help.header");
        mm.send(sender, "commands.obu.help.status");
        mm.send(sender, "commands.obu.help.sandbox");
        mm.send(sender, "commands.obu.help.context");
        mm.send(sender, "commands.obu.help.clear");
        mm.send(sender, "commands.obu.help.defaults");
        mm.send(sender, "commands.obu.help.settings");
        mm.send(sender, "commands.obu.help.wiki");
        return Command.SINGLE_SUCCESS;
    }
}
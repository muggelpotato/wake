package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class OBUHelpCommand {

    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.literal("-help", "wake.obu.commands.help")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    plugin.getMessageManager().send(sender, "commands.obu.help.header");
                    plugin.getMessageManager().send(sender, "commands.obu.help.status");
                    plugin.getMessageManager().send(sender, "commands.obu.help.sandbox");
                    plugin.getMessageManager().send(sender, "commands.obu.help.context");
                    plugin.getMessageManager().send(sender, "commands.obu.help.clear");
                    plugin.getMessageManager().send(sender, "commands.obu.help.defaults");
                    plugin.getMessageManager().send(sender, "commands.obu.help.settings");
                    plugin.getMessageManager().send(sender, "commands.obu.help.wiki");
                    return Command.SINGLE_SUCCESS;
                }));
    }
}

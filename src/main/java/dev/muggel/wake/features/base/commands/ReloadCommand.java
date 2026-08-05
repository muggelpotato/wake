package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class ReloadCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("reload")
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        List<Component> feedback = plugin.reloadSettings();
        plugin.getMessageManager().send(sender, "commands.reload.success");
        for (Component outcome : feedback) {
            sender.sendMessage(outcome);
        }
        return Command.SINGLE_SUCCESS;
    }
}
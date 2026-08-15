package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxExitCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("exit")
                .executesPlayer((ctx, player) -> execute(ctx, player, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        if (OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId()) == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.none_active");
            return 0;
        }
        OBUCommandHelper.delivery(plugin).applyDefaultContext(player);
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.exited");
        return Command.SINGLE_SUCCESS;
    }
}
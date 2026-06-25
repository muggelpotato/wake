package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import java.util.List;

public class WakeReloadCommand {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.literal("reload", "wake.command.wake.reload")
                .executes(ctx -> execute(ctx, plugin)));
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        List<Component> feedback = plugin.reloadSettings();

        plugin.getMessageManager().send(sender, "commands.reload.success");
        for (Component msg : feedback) {
            sender.sendMessage(msg);
        }
        return Command.SINGLE_SUCCESS;
    }
}

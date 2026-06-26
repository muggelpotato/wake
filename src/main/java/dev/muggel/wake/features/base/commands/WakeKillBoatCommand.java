package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.base.BaseModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

public class WakeKillBoatCommand {
    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.moduleLiteral("killboatonexit", "wake.command.wake.killboatonexit", plugin, BaseModule.class)
                .then(Commands.argument("state", BoolArgumentType.bool())
                        .executes(ctx -> execute(ctx, plugin))));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();

        boolean killState = BoolArgumentType.getBool(ctx, "state");
        plugin.getStateManager().set("killboatonexit", killState);

        plugin.getMessageManager().send(sender, "commands.setting_updated",
                Placeholder.parsed("state", String.valueOf(killState)));

        return Command.SINGLE_SUCCESS;
    }
}

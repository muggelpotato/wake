package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.base.BaseModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.jspecify.annotations.NonNull;

public class WakeKillEmptyBoatsCommand {
    public static void register(@NonNull LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        root.then(WakeCommandBuilder.moduleLiteral("killemptyboats", "wake.command.wake.killemptyboats", plugin, BaseModule.class)
                .executes(ctx -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        int killed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Boat boat : world.getEntitiesByClass(Boat.class)) {
                if (boat.getPassengers().isEmpty()) {
                    boat.remove();
                    killed++;
                }
            }
        }
        plugin.getMessageManager().send(sender, "commands.killemptyboats.success", Placeholder.parsed("amount", String.valueOf(killed)));
        return Command.SINGLE_SUCCESS;
    }
}

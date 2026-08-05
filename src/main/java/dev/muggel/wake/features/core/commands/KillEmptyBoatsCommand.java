package dev.muggel.wake.features.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.jspecify.annotations.NonNull;

public class KillEmptyBoatsCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("killemptyboats")
                .withPreset(PermissionPreset.BUILDER)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        int killed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Boat boat : world.getEntitiesByClass(Boat.class)) {
                if (boat.getPassengers().isEmpty()) {
                    boat.remove();
                    killed++;
                }
            }
        }
        plugin.getMessageManager().send(ctx.getSource().getSender(), "commands.killemptyboats.success", Placeholder.unparsed("amount", String.valueOf(killed)));
        return Command.SINGLE_SUCCESS;
    }
}
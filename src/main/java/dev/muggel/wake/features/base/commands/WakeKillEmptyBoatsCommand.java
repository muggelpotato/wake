package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.base.BaseModule;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Boat;

public class WakeKillEmptyBoatsCommand {
    public static CommandNode getNode(Wake plugin) {
        return CommandNode.literal("killemptyboats")
                .withModule(BaseModule.class)
                .executesSender((ctx, sender) -> {
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
                });
    }
}

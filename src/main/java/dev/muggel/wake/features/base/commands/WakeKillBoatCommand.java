package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.base.BaseModule;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public class WakeKillBoatCommand {
    public static CommandNode getNode(Wake plugin) {
        return CommandNode.literal("killboatonexit")
                .withModule(BaseModule.class)
                .addSubcommand(CommandNode.argument("state", BoolArgumentType.bool())
                        .executesSender((ctx, sender) -> {
                            boolean killState = BoolArgumentType.getBool(ctx, "state");
                            plugin.getStateManager().set("killboatonexit", killState);
                            plugin.getMessageManager().send(sender, "commands.setting_updated",
                                    Placeholder.parsed("state", String.valueOf(killState)));
                            return Command.SINGLE_SUCCESS;
                        }));
    }
}

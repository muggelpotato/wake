package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUModule;

public class OBUHelpCommand {

    public static CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-help")
                .withModule(OBUModule.class)
                .executesSender((ctx, sender) -> {
                    plugin.getMessageManager().send(sender, "commands.obu.help.header");
                    plugin.getMessageManager().send(sender, "commands.obu.help.status");
                    plugin.getMessageManager().send(sender, "commands.obu.help.sandbox");
                    plugin.getMessageManager().send(sender, "commands.obu.help.context");
                    plugin.getMessageManager().send(sender, "commands.obu.help.clear");
                    plugin.getMessageManager().send(sender, "commands.obu.help.defaults");
                    plugin.getMessageManager().send(sender, "commands.obu.help.settings");
                    plugin.getMessageManager().send(sender, "commands.obu.help.wiki");
                    return Command.SINGLE_SUCCESS;
                });
    }
}
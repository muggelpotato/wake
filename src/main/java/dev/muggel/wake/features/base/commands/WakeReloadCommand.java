package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.base.BaseModule;
import net.kyori.adventure.text.Component;

import java.util.List;

public class WakeReloadCommand {
    public static CommandNode getNode(Wake plugin) {
        return CommandNode.literal("reload")
                .withModule(BaseModule.class)
                .executesSender((ctx, sender) -> {
                    List<Component> feedback = plugin.reloadSettings();
                    plugin.getMessageManager().send(sender, "commands.reload.success");
                    for (Component msg : feedback) {
                        sender.sendMessage(msg);
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }
}

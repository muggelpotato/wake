package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionManager;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.OBUModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class HelpCommand {
    private static final String LITERAL = "-help";
    private static final String KEY_PREFIX = "commands.obu.help.";

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal(LITERAL)
                .withPreset(PermissionPreset.PLAYER)
                .withGate(CommandNode.Gate.OPEN)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager mm = plugin.getMessageManager();
        CommandNode root = WakeCommandManager.rootOf(OBUModule.class);
        mm.send(sender, KEY_PREFIX + "header");
        boolean settingsListed = false;
        for (CommandNode child : root == null ? List.<CommandNode>of() : root.getChildren()) {
            if (child.isArgument() || child.getName().equals(LITERAL)
                    || !PermissionManager.canReach(sender, child.getPermission())) {
                continue;
            }
            OBUDefinition def = OBUDefinition.get(child.getName());
            if (def != null && def != OBUDefinition.reset) {
                if (!settingsListed) {
                    mm.send(sender, KEY_PREFIX + "settings");
                    settingsListed = true;
                }
                continue;
            }
            String helpKey = child.getHelpKey();
            mm.send(sender, helpKey != null ? helpKey : KEY_PREFIX + "fallback",
                    Placeholder.unparsed("command", child.getName()));
        }
        mm.send(sender, KEY_PREFIX + "wiki");
        return Command.SINGLE_SUCCESS;
    }
}
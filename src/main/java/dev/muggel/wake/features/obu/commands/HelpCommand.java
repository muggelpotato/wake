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
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class HelpCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-help")
                .withPreset(PermissionPreset.PLAYER)
                .withGate(CommandNode.Gate.OPEN)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager mm = plugin.getMessageManager();
        CommandNode root = WakeCommandManager.rootOf(OBUModule.class);
        mm.send(sender, "commands.obu.help.header");
        sendIfAllowed(mm, sender, root, "-status", "commands.obu.help.status");
        sendIfAllowed(mm, sender, root, "-sandbox", "commands.obu.help.sandbox");
        sendIfAllowed(mm, sender, root, "-context", "commands.obu.help.context");
        sendIfAllowed(mm, sender, root, "-clear", "commands.obu.help.clear");
        sendIfAllowed(mm, sender, root, "-defaults", "commands.obu.help.defaults");
        if (canUseAnySetting(sender, root)) {
            mm.send(sender, "commands.obu.help.settings");
        }
        sendIfAllowed(mm, sender, root, OBUDefinition.reset.commandName(), "commands.obu.help.reset");
        sendIfAllowed(mm, sender, root, "-settings", "commands.obu.help.config");
        mm.send(sender, "commands.obu.help.wiki");
        return Command.SINGLE_SUCCESS;
    }

    private static void sendIfAllowed(MessageManager mm, CommandSender sender, @Nullable CommandNode root, String literal, String key) {
        CommandNode node = childNamed(root, literal);
        if (node != null && PermissionManager.hasAccess(sender, node.getPermission())) {
            mm.send(sender, key);
        }
    }

    private static boolean canUseAnySetting(CommandSender sender, @Nullable CommandNode root) {
        if (root == null) {
            return false;
        }
        for (CommandNode child : root.getChildren()) {
            OBUDefinition def = OBUDefinition.get(child.getName());
            if (def != null && def != OBUDefinition.reset && PermissionManager.hasAccess(sender, child.getPermission())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable CommandNode childNamed(@Nullable CommandNode root, String literal) {
        if (root == null) {
            return null;
        }
        for (CommandNode child : root.getChildren()) {
            if (child.getName().equals(literal)) {
                return child;
            }
        }
        return null;
    }
}
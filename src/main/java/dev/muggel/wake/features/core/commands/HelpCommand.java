package dev.muggel.wake.features.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionManager;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.text.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class HelpCommand {
    private static final int HOVER_SUBCOMMAND_CAP = 12;
    private static final JoinConfiguration ALIAS_SEPARATOR = JoinConfiguration.separator(Component.text(", "));

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("help")
                .withPreset(PermissionPreset.PLAYER, PermissionPreset.BUILDER)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager mm = plugin.getMessageManager();
        mm.send(sender, "commands.help.header");
        for (CommandNode root : WakeCommandManager.rootsVisibleTo(plugin, sender)) {
            mm.send(sender, "commands.help.entry",
                    Placeholder.parsed("command", root.getName()),
                    Placeholder.component("aliases", aliasText(mm, root)),
                    Placeholder.component("description", CommandHelper.moduleDescription(plugin, root)),
                    Placeholder.component("subcommands", subcommandHover(mm, sender, root)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull Component aliasText(@NonNull MessageManager mm, @NonNull CommandNode root) {
        List<Component> aliases = root.getAliases().stream()
                .map(alias -> mm.getComponent("commands.help.alias", Placeholder.unparsed("alias", alias)))
                .toList();
        if (aliases.isEmpty()) {
            return Component.empty();
        }
        return mm.getComponent("commands.help.aliases",
                Placeholder.component("aliases", Component.join(ALIAS_SEPARATOR, aliases)));
    }

    private static @NonNull Component subcommandHover(@NonNull MessageManager mm, @NonNull CommandSender sender, @NonNull CommandNode root) {
        List<Component> lines = new ArrayList<>();
        lines.add(mm.getComponent("commands.help.hover_header",
                Placeholder.unparsed("command", root.getName())));
        List<CommandNode> literals = root.getChildren().stream()
                .filter(child -> !child.isArgument() && PermissionManager.canReach(sender, child.getPermission()))
                .toList();
        for (CommandNode child : literals.subList(0, Math.min(literals.size(), HOVER_SUBCOMMAND_CAP))) {
            lines.add(mm.getComponent("commands.help.hover_line",
                    Placeholder.unparsed("command", root.getName()),
                    Placeholder.unparsed("sub", child.getName())));
        }
        if (literals.size() > HOVER_SUBCOMMAND_CAP) {
            lines.add(mm.getComponent("commands.help.hover_more",
                    Placeholder.unparsed("count", String.valueOf(literals.size() - HOVER_SUBCOMMAND_CAP))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
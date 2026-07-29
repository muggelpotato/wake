package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionManager;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.module.WakeModule;
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

    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("help")
                .withPreset(PermissionPreset.PLAYER)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager mm = plugin.getMessageManager();
        mm.send(sender, "commands.help.header");
        for (CommandNode root : WakeCommandManager.getRegisteredRoots()) {
            String moduleId = WakeCommandManager.moduleIdOf(plugin, root);
            if (moduleId == null) {
                continue;
            }
            Class<? extends WakeModule> moduleClass = root.getModuleClass();
            if (moduleClass != null && plugin.getModule(moduleClass) == null) {
                continue;
            }
            if (!PermissionManager.hasAccess(sender, root.getPermission())) {
                continue;
            }
            mm.send(sender, "commands.help.entry",
                    Placeholder.parsed("command", root.getName()),
                    Placeholder.unparsed("aliases", aliasText(root)),
                    Placeholder.component("description", CommandHelper.moduleDescription(plugin, moduleId, root)),
                    Placeholder.component("subcommands", subcommandHover(plugin, sender, root)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull String aliasText(@NonNull CommandNode root) {
        List<String> aliases = root.getAliases();
        if (aliases.isEmpty()) {
            return "";
        }
        return "(/" + String.join(", /", aliases) + ")";
    }

    private static @NonNull Component subcommandHover(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull CommandNode root) {
        MessageManager mm = plugin.getMessageManager();
        List<Component> lines = new ArrayList<>();
        lines.add(mm.getComponent("commands.help.hover_header",
                Placeholder.unparsed("command", root.getName())));
        List<CommandNode> literals = root.getChildren().stream()
                .filter(child -> !child.isArgument() && PermissionManager.hasAccess(sender, child.getPermission()))
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
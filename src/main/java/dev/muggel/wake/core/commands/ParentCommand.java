package dev.muggel.wake.core.commands;

import dev.muggel.wake.core.WakeColors;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * subcommands container
 * handles routing and tab completion for subcommands
 */
public abstract class ParentCommand extends BaseCommand {
    protected final Map<String, SubCommand> subCommands = new HashMap<>();

    protected ParentCommand(@NotNull String name) {
        super(name);
    }

    protected void registerSubCommand(SubCommand subCommand) {
        subCommands.put(subCommand.getName().toLowerCase(), subCommand);
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        SubCommand sub = subCommands.get(args[0].toLowerCase());
        if (sub == null) {
            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Unknown subcommand. Type /" + label + " for help.", WakeColors.ERROR)));
            return true;
        }

        if (sub.getPermission() != null && !sender.hasPermission(sub.getPermission())) {
            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("No permission.", WakeColors.ERROR)));
            return true;
        }

        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, args.length - 1);
        
        sub.execute(sender, label, subArgs);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String current = args[0].toLowerCase();
            return subCommands.values().stream()
                    .filter(sub -> sub.getPermission() == null || sender.hasPermission(sub.getPermission()))
                    .map(SubCommand::getName)
                    .filter(name -> name.startsWith(current))
                    .sorted()
                    .collect(Collectors.toList());
        }

        SubCommand sub = subCommands.get(args[0].toLowerCase());
        if (sub != null) {
            if (sub.getPermission() != null && !sender.hasPermission(sub.getPermission())) {
                return Collections.emptyList();
            }
            String[] subArgs = new String[args.length - 1];
            System.arraycopy(args, 1, subArgs, 0, args.length - 1);
            return sub.suggest(sender, alias, subArgs);
        }

        return Collections.emptyList();
    }

    protected void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("--- " + getName().toUpperCase() + " COMMANDS ---", WakeColors.SECONDARY));
        subCommands.values().stream()
                .filter(sub -> sub.getPermission() == null || sender.hasPermission(sub.getPermission()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .forEach(sub -> sender.sendMessage(Component.text(" /" + label + " " + sub.getName(), WakeColors.PRIMARY)));
    }
}

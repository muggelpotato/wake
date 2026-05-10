package dev.muggel.wake.commands;

import dev.muggel.wake.Wake;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WakeCommand extends Command {
    private final Wake plugin;

    public WakeCommand(Wake plugin) {
        super("wake");
        this.plugin = plugin;
        this.setAliases(List.of("wa"));
        this.setDescription("Main command for the Wake plugin.");
        this.setUsage("/wake <subcommand>");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NonNull [] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Wake Plugin Commands:", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/" + commandLabel + " reload ", NamedTextColor.AQUA)
                    .append(Component.text("- Reloads the configuration", NamedTextColor.GRAY)));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage(Component.text("[Wake] ", NamedTextColor.YELLOW)
                        .append(Component.text("Configuration reloaded! Changes are live.", NamedTextColor.GREEN)));
                break;
            default:
                sender.sendMessage(Component.text("Unknown subcommand. Type /" + commandLabel + " for help.", NamedTextColor.RED));
                break;
        }

        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NonNull [] args) {
        if (args.length == 1) {
            List<String> subCommands = List.of("reload");
            String current = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();

            for (String cmd : subCommands) {
                if (cmd.startsWith(current)) {
                    matches.add(cmd);
                }
            }
            return matches;
        }
        return Collections.emptyList();
    }
}
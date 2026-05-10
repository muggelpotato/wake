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
    private static final String RELOAD_PERMISSION = "wake.reload";
    private static final String ADMIN_PERMISSION = "wake.admin";
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
            sender.sendMessage(Component.text("/" + commandLabel + " reload ", NamedTextColor.AQUA).append(Component.text("- Reloads the configuration", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/" + commandLabel + " killboatonexit <true/false> ", NamedTextColor.AQUA).append(Component.text("- Toggles boat removal on exit", NamedTextColor.GRAY)));
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "reload":
                if (!sender.hasPermission(RELOAD_PERMISSION)) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                plugin.reloadSettings();
                sender.sendMessage(Component.text("[Wake] ", NamedTextColor.YELLOW)
                        .append(Component.text("Configuration reloaded", NamedTextColor.GREEN)));
                break;
            case "killboatonexit":
                if (!sender.hasPermission(ADMIN_PERMISSION)) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /" + commandLabel + " killboatonexit <true|false>", NamedTextColor.RED));
                    return true;
                }
                String raw = args[1].toLowerCase();
                if (!raw.equals("true") && !raw.equals("false")) {
                    sender.sendMessage(Component.text("Usage: /" + commandLabel + " killboatonexit <true|false>", NamedTextColor.RED));
                    return true;
                }
                boolean killState = raw.equals("true");
                plugin.setKillBoatOnExit(killState);
                sender.sendMessage(Component.text("[Wake] Auto-kill boat set to ", NamedTextColor.YELLOW)
                        .append(Component.text(String.valueOf(killState), NamedTextColor.AQUA)));
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
            String current = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();

            if (sender.hasPermission(RELOAD_PERMISSION) && "reload".startsWith(current)) {
                matches.add("reload");
                }
            if (sender.hasPermission(ADMIN_PERMISSION) && "killboatonexit".startsWith(current)) {
                matches.add("killboatonexit");
            }
            return matches;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("killboatonexit")) {
            return List.of("true", "false");
        }
        return Collections.emptyList();
    }
}
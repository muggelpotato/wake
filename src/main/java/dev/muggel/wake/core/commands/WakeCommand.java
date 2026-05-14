package dev.muggel.wake.core.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.GeneralModule;
import dev.muggel.wake.core.WakeColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class WakeCommand extends ParentCommand {

    public WakeCommand(Wake plugin) {
        super("wake");
        this.setAliases(List.of("wa"));
        this.setDescription("Main command for the Wake plugin.");
        this.setUsage("/wake <subcommand>");
        this.setParentPermission("wake.commands");

        registerSubCommand(new ReloadSubCommand(plugin));
        registerSubCommand(new KillBoatSubCommand(plugin));
    }

    private static class ReloadSubCommand implements SubCommand {
        private final Wake plugin;

        public ReloadSubCommand(Wake plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() { return "reload"; }

        @Override
        public String getPermission() { return "wake.commands.reload"; }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            plugin.reloadSettings();
            GeneralModule general = plugin.getModule(GeneralModule.class);
            if (general != null) general.reload(plugin);

            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Configuration reloaded", WakeColors.PRIMARY)));
        }
    }

    private static class KillBoatSubCommand implements SubCommand {
        private final Wake plugin;

        public KillBoatSubCommand(Wake plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() { return "killboatonexit"; }

        @Override
        public String getPermission() { return "wake.commands.killboatonexit"; }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            GeneralModule general = plugin.getModule(GeneralModule.class);
            if (general == null) return;

            if (args.length < 1) {
                sender.sendMessage(Component.text("Usage: /" + label + " killboatonexit <true|false>", WakeColors.ERROR));
                return;
            }
            String raw = args[0].toLowerCase();
            if (!raw.equals("true") && !raw.equals("false")) {
                sender.sendMessage(Component.text("Usage: /" + label + " killboatonexit <true|false>", WakeColors.ERROR));
                return;
            }
            boolean killState = raw.equals("true");
            general.setKillBoatOnExit(killState);
            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Auto-kill boat set to ", WakeColors.NEUTRAL))
                    .append(Component.text(String.valueOf(killState), WakeColors.PRIMARY)));
        }

        @Override
        public List<String> suggest(CommandSender sender, String label, String[] args) {
            if (args.length == 1) {
                return SmartCompleter.filter(args[0], SmartCompleter.BOOLEAN);
            }
            return List.of();
        }
    }
}

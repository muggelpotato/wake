package dev.muggel.wake.core.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.core.WakeModule;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.List;

public class WakeKillBoatSubCommand implements SubCommand {
    private final Wake plugin;

    public WakeKillBoatSubCommand(Wake plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() { return "killboatonexit"; }

    @Override
    public String getPermission() { return "wake.commands.killboatonexit"; }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        WakeModule core = plugin.getModule(WakeModule.class);
        if (core == null) {
            sender.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Core module is disabled.", WakeColors.ERROR)));
            return;
        }

        if (args.length != 1) {
            sender.sendMessage(Component.text("Usage: /" + label + " killboatonexit <true|false>", WakeColors.ERROR));
            return;
        }
        String raw = args[0].toLowerCase();
        if (!raw.equals("true") && !raw.equals("false")) {
            sender.sendMessage(Component.text("Usage: /" + label + " killboatonexit <true|false>", WakeColors.ERROR));
            return;
        }
        boolean killState = raw.equals("true");
        core.setKillBoatOnExit(killState);
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

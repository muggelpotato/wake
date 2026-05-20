package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.OBUModule;
import dev.muggel.wake.core.commands.SubCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public class OBUHelpSubCommand implements SubCommand {

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getPermission() {
        return "wake.obu.commands.help";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String url = "https://openboatutils.github.io/commands.html";

        Component message = Component.text("[OBU Help] ", WakeColors.SECONDARY)
                .append(Component.text("Click here to read the Wiki: ", WakeColors.NEUTRAL))
                .append(Component.text("Settings & Commands", WakeColors.PRIMARY))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open in Browser", WakeColors.ACCENT)));

        sender.sendMessage(message);
    }
}

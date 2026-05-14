package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.core.commands.BaseCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class OBUHelpCommand extends BaseCommand {

    public OBUHelpCommand() {
        super("obuhelp");
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Links to the official OpenBoatUtils Wiki");
        this.setUsage("/obuhelp");
        this.setPlayerOnly(true);
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        Player player = (Player) sender;
        String url = "https://openboatutils.github.io/commands.html";

        Component message = Component.text("[OBU Help] ", WakeColors.SECONDARY)
                .append(Component.text("Click here to read the Wiki: ", WakeColors.NEUTRAL))
                .append(Component.text("Settings & Commands", WakeColors.PRIMARY))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open in Browser", WakeColors.ACCENT)));

        player.sendMessage(message);
        return true;
    }
}
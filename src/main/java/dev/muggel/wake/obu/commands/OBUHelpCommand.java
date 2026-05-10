package dev.muggel.wake.obu.commands;

import dev.muggel.wake.obu.OBUManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.List;

public class OBUHelpCommand extends Command {

    public OBUHelpCommand() {
        super("obuhelp");
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Links to the official OpenBoatUtils Wiki");
        this.setUsage("/obuhelp");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NonNull [] args) {
        if (!(sender instanceof Player player)) return true;
        String url = "https://openboatutils.github.io/commands.html";

        Component message = Component.text("[OBU Help] ", NamedTextColor.GRAY)
                .append(Component.text("Click here to read the Wiki: ", NamedTextColor.WHITE))
                .append(Component.text("Settings & Commands", NamedTextColor.YELLOW))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open in Browser", NamedTextColor.GREEN)));

        player.sendMessage(message);
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NonNull [] args) {
        return Collections.emptyList();
    }
}
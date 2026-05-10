package dev.muggel.wake.obu.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.obu.config.OBUConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OBUProfileCommand extends Command {
    private final Wake plugin;
    private final OBUConfigManager configManager;

    public OBUProfileCommand(Wake plugin, OBUConfigManager configManager) {
        super("obuprofile");
        this.plugin = plugin;
        this.configManager = configManager;
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Lists or applies OBU profiles");
        this.setUsage("/obuprofile [profile]");
    }

    private Component getProfileHoverText(String profileName) {
        ConfigurationSection profileData = plugin.getConfig().getConfigurationSection("obu.profiles." + profileName);
        Component hoverText = Component.text("Settings for " + profileName + ":", NamedTextColor.YELLOW);

        if (profileData != null) {
            for (String key : profileData.getKeys(false)) {
                String val = profileData.getString(key);
                hoverText = hoverText.append(Component.newline())
                        .append(Component.text("  - ", NamedTextColor.GRAY))
                        .append(Component.text(key, NamedTextColor.AQUA))
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(Component.text(val != null ? val : "none", NamedTextColor.WHITE));
            }
        }
        return hoverText;
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NonNull [] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission(OBUManager.OBU_PERMISSION)) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        ConfigurationSection profilesSection = plugin.getConfig().getConfigurationSection("obu.profiles");
        if (profilesSection == null || profilesSection.getKeys(false).isEmpty()) {
            player.sendMessage(Component.text("No profiles found in config.yml.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Available OBU Profiles:", NamedTextColor.YELLOW));

            for (String profileName : profilesSection.getKeys(false)) {
                Component hoverText = getProfileHoverText(profileName)
                        .append(Component.newline()).append(Component.newline())
                        .append(Component.text("Click to apply!", NamedTextColor.GREEN));

                Component profileComp = Component.text(" - ", NamedTextColor.GRAY)
                        .append(Component.text(profileName, NamedTextColor.AQUA))
                        .clickEvent(ClickEvent.runCommand("/obuprofile " + profileName))
                        .hoverEvent(HoverEvent.showText(hoverText));

                player.sendMessage(profileComp);
            }
            return true;
        }

        String profileName = args[0];
        if (!profilesSection.contains(profileName)) {
            player.sendMessage(Component.text("Profile '" + profileName + "' does not exist.", NamedTextColor.RED));
            return true;
        }

        List<String> appliedSettings = configManager.resetAndApplyProfile(player, profileName);
        if (appliedSettings.isEmpty()) {
            player.sendMessage(Component.text("[Wake] ", NamedTextColor.YELLOW)
                    .append(Component.text("Failed to apply profile. Check server logs.", NamedTextColor.RED)));
            return true;
        }
        Component hoverText = getProfileHoverText(profileName);
        player.sendMessage(Component.text("[Wake] ", NamedTextColor.YELLOW)
                .append(Component.text("Applied profile: ", NamedTextColor.WHITE))
                .append(Component.text(profileName, NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(hoverText))));

        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NonNull [] args) {
        if (args.length == 1) {
            ConfigurationSection profilesSection = plugin.getConfig().getConfigurationSection("obu.profiles");
            if (profilesSection != null) {
                String current = args[0].toLowerCase();
                List<String> matches = new ArrayList<>();
                for (String key : profilesSection.getKeys(false)) {
                    if (key.toLowerCase().startsWith(current)) {
                        matches.add(key);
                    }
                }
                return matches;
            }
        }
        return Collections.emptyList();
    }
}
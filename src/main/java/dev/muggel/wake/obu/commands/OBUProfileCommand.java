package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.obu.config.OBUConfigManager;
import dev.muggel.wake.core.commands.BaseCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class OBUProfileCommand extends BaseCommand {
    private final Wake plugin;
    private final OBUConfigManager configManager;

    public OBUProfileCommand(Wake plugin, OBUConfigManager configManager) {
        super("obuprofile");
        this.plugin = plugin;
        this.configManager = configManager;
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Lists or applies OBU profiles");
        this.setUsage("/obuprofile [profile]");
        this.setPlayerOnly(true);
    }

    private Component getProfileHoverText(String profileName) {
        ConfigurationSection profileData = plugin.getConfig().getConfigurationSection("obu.profiles." + profileName);
        Component hoverText = Component.text("Settings for " + profileName + ":", WakeColors.SECONDARY);

        if (profileData != null) {
            for (String key : profileData.getKeys(false)) {
                Object rawVal = profileData.get(key);
                String val = rawVal != null ? rawVal.toString() : null;
                hoverText = hoverText.append(Component.newline())
                        .append(Component.text("  - ", WakeColors.NEUTRAL))
                        .append(Component.text(key, WakeColors.ACCENT))
                        .append(Component.text(": ", WakeColors.NEUTRAL))
                        .append(Component.text(val != null ? val : "none", WakeColors.PRIMARY));
            }
        }
        return hoverText;
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        Player player = (Player) sender;

        ConfigurationSection profilesSection = plugin.getConfig().getConfigurationSection("obu.profiles");
        if (profilesSection == null || profilesSection.getKeys(false).isEmpty()) {
            player.sendMessage(Component.text("No profiles found in config.yml.", WakeColors.ERROR));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Available OBU Profiles:", WakeColors.SECONDARY));

            for (String profileName : profilesSection.getKeys(false)) {
                Component hoverText = getProfileHoverText(profileName)
                        .append(Component.newline()).append(Component.newline())
                        .append(Component.text("Click to apply!", WakeColors.PRIMARY));

                Component profileComp = Component.text(" - ", WakeColors.NEUTRAL)
                        .append(Component.text(profileName, WakeColors.ACCENT))
                        .clickEvent(ClickEvent.runCommand("/obuprofile " + profileName))
                        .hoverEvent(HoverEvent.showText(hoverText));

                player.sendMessage(profileComp);
            }
            return true;
        }

        String profileName = args[0];
        if (!profilesSection.contains(profileName)) {
            player.sendMessage(Component.text("Profile '" + profileName + "' does not exist.", WakeColors.ERROR));
            return true;
        }

        List<String> appliedSettings = configManager.resetAndApplyProfile(player, profileName);
        if (appliedSettings.isEmpty()) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Failed to apply profile. Check server logs.", WakeColors.ERROR)));
            return true;
        }
        Component hoverText = getProfileHoverText(profileName);
        player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                .append(Component.text("Applied profile: ", WakeColors.NEUTRAL))
                .append(Component.text(profileName, WakeColors.PRIMARY)
                        .hoverEvent(HoverEvent.showText(hoverText))));

        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            ConfigurationSection profilesSection = plugin.getConfig().getConfigurationSection("obu.profiles");
            if (profilesSection != null) {
                return SmartCompleter.filter(args[0], profilesSection.getKeys(false));
            }
        }
        return Collections.emptyList();
    }
}
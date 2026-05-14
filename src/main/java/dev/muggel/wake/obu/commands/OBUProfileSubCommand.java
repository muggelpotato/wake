package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.OBUModule;
import dev.muggel.wake.obu.config.OBUProfileManager;
import dev.muggel.wake.obu.model.OBUProfile;
import dev.muggel.wake.obu.model.OBUSetting;
import dev.muggel.wake.obu.service.OBUService;
import dev.muggel.wake.core.commands.SubCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class OBUProfileSubCommand implements SubCommand {
    private final OBUProfileManager profileManager;
    private final OBUService obuService;

    public OBUProfileSubCommand(OBUProfileManager profileManager, OBUService obuService) {
        this.profileManager = profileManager;
        this.obuService = obuService;
    }

    @Override
    public String getName() {
        return "profile";
    }

    @Override
    public String getPermission() {
        return OBUModule.OBU_PERMISSION;
    }

    private Component getProfileHoverText(OBUProfile profile) {
        Component hoverText = Component.text("Settings for " + profile.name() + ":", WakeColors.SECONDARY);

        for (OBUSetting setting : profile.getSettings()) {
            hoverText = hoverText.append(Component.newline())
                    .append(Component.text("  - ", WakeColors.NEUTRAL))
                    .append(Component.text(setting.definition().name(), WakeColors.ACCENT))
                    .append(Component.text(": ", WakeColors.NEUTRAL))
                    .append(Component.text(String.join(", ", setting.args()), WakeColors.PRIMARY));
        }
        return hoverText;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be executed by players.", WakeColors.ERROR));
            return;
        }

        if (profileManager.getProfileNames().isEmpty()) {
            player.sendMessage(Component.text("No profiles found in config.yml.", WakeColors.ERROR));
            return;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Available OBU Profiles:", WakeColors.SECONDARY));

            for (String profileName : profileManager.getProfileNames()) {
                OBUProfile profile = profileManager.getProfile(profileName);
                Component hoverText = getProfileHoverText(profile)
                        .append(Component.newline()).append(Component.newline())
                        .append(Component.text("Click to apply!", WakeColors.PRIMARY));

                Component profileComp = Component.text(" - ", WakeColors.NEUTRAL)
                        .append(Component.text(profileName, WakeColors.ACCENT))
                        .clickEvent(ClickEvent.runCommand("/" + label + " profile " + profileName))
                        .hoverEvent(HoverEvent.showText(hoverText));

                player.sendMessage(profileComp);
            }
            return;
        }

        String profileName = args[0];
        OBUProfile profile = profileManager.getProfile(profileName);
        if (profile == null) {
            player.sendMessage(Component.text("Profile '" + profileName + "' does not exist.", WakeColors.ERROR));
            return;
        }

        obuService.applyDefaultProfile(player, profileManager);
        
        if (!"default".equalsIgnoreCase(profileName)) {
            obuService.applyProfile(player, profile);
        }
        
        Component hoverText = getProfileHoverText(profile);
        player.sendMessage(WakeColors.prefix()
                .append(Component.text("Applied profile: ", WakeColors.NEUTRAL))
                .append(Component.text(profileName, WakeColors.PRIMARY)
                        .hoverEvent(HoverEvent.showText(hoverText))));
    }

    @Override
    public List<String> suggest(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            return SmartCompleter.filter(args[0], profileManager.getProfileNames());
        }
        return Collections.emptyList();
    }
}

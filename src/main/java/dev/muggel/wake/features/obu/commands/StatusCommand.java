package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StatusCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-status")
                .withHelpKey("commands.obu.help.status")
                .withPreset(PermissionPreset.PLAYER)
                .executesPlayer((ctx, player) -> execute(ctx, player, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Player player, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String activeSandbox = service.getPlayerActiveSandbox(player);
        String contextName = service.getActiveContextName(player);
        boolean inBoat = player.getVehicle() instanceof Boat;
        plugin.getMessageManager().send(sender, "commands.obu.status.player");
        String playerBaseName = activeSandbox != null ? activeSandbox : contextName;
        Boat targetedBoat = null;
        if (inBoat) {
            targetedBoat = (Boat) player.getVehicle();
        } else {
            Entity targetEntity = player.getTargetEntity(CommandNode.AIM_DISTANCE);
            if (targetEntity instanceof Boat boat) {
                targetedBoat = boat;
            }
        }
        Set<String> boatOverriddenKeys = new HashSet<>();
        OBUContext boatBase = null;
        Map<String, OBUSetting> boatOverrides = new HashMap<>();
        String boatContextStr;
        if (targetedBoat != null) {
            boatContextStr = service.getBoatContextName(targetedBoat);
            if (boatContextStr != null) {
                boatBase = contextManager.getContext(boatContextStr);
            }
            boatOverrides = service.getSyncManager().getLocalOverrides(targetedBoat.getUniqueId());
            boatOverriddenKeys.addAll(boatOverrides.keySet());
        }
        OBUContext playerBase = contextManager.getContext(playerBaseName);
        Map<String, OBUSetting> playerOverrides = service.getSyncManager().getLocalOverrides(player.getUniqueId());
        if (playerBase == null && playerOverrides.isEmpty()) {
            plugin.getMessageManager().send(sender, "commands.obu.status.empty");
        } else {
            if (playerBase != null) {
                OBUContext defaultContext = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
                List<OBUSetting> customSettings = new ArrayList<>(playerBase.settings());
                List<OBUSetting> inheritedDefaultSettings = new ArrayList<>();
                boolean blankSlate = activeSandbox != null;
                if (!blankSlate && OBUContextManager.inheritsDefault(playerBase) && defaultContext != null) {
                    Set<String> customKeys = new HashSet<>();
                    for (OBUSetting s : customSettings) {
                        customKeys.add(s.getUniqueKey());
                    }
                    for (OBUSetting def : defaultContext.settings()) {
                        if (!customKeys.contains(def.getUniqueKey())) {
                            inheritedDefaultSettings.add(def);
                        }
                    }
                }
                if (!customSettings.isEmpty() || inheritedDefaultSettings.isEmpty()) {
                    plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.unparsed("context", OBUContextManager.displayName(playerBaseName)));
                    if (customSettings.isEmpty()) {
                        plugin.getMessageManager().send(sender, "commands.obu.status.empty");
                    } else {
                        printSettingsList(plugin, sender, customSettings, playerOverrides, boatOverriddenKeys);
                    }
                }
                if (!inheritedDefaultSettings.isEmpty()) {
                    plugin.getMessageManager().send(sender, "commands.obu.status.subtitle", Placeholder.parsed("context", OBUContextManager.DEFAULT_CONTEXT));
                    printSettingsList(plugin, sender, inheritedDefaultSettings, playerOverrides, boatOverriddenKeys);
                }
            }
            if (!playerOverrides.isEmpty()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.temp");
                printSettingsList(plugin, sender, new ArrayList<>(playerOverrides.values()), null, boatOverriddenKeys);
            }
        }
        if (targetedBoat != null) {
            plugin.getMessageManager().send(sender, "commands.obu.status.boat");
            if (boatBase == null && boatOverrides.isEmpty()) {
                plugin.getMessageManager().send(sender, "commands.obu.status.empty");
            } else {
                if (boatBase != null) {
                    printSettingsList(plugin, sender, boatBase.settings(), boatOverrides, Collections.emptySet());
                }
                if (!boatOverrides.isEmpty()) {
                    plugin.getMessageManager().send(sender, "commands.obu.status.temp");
                    printSettingsList(plugin, sender, new ArrayList<>(boatOverrides.values()), null, Collections.emptySet());
                }
            }
        }
        if (activeSandbox == null) {
            CommandHelper.sendHint(plugin, sender, "commands.obu.status.hint");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void printSettingsList(Wake plugin, CommandSender audience, @NonNull List<OBUSetting> settings, Map<String, OBUSetting> overrides, Set<String> boatOverriddenKeys) {
        for (OBUSetting setting : settings) {
            boolean isOverriddenByTemp = overrides != null && overrides.containsKey(setting.getUniqueKey());
            boolean isOverriddenByBoat = boatOverriddenKeys.contains(setting.getUniqueKey());
            boolean isOverridden = isOverriddenByBoat || isOverriddenByTemp;

            Component settingComp = plugin.getMessageManager().getComponent(
                    isOverridden ? "commands.obu.status.overridden" : "commands.obu.status.line",
                    Placeholder.parsed("name", setting.definition().name()),
                    Placeholder.unparsed("value", String.join(", ", OBUCommandHelper.displayArgs(setting))));
            if (isOverridden) {
                if (isOverriddenByBoat) {
                    settingComp = settingComp.append(plugin.getMessageManager().getComponent("commands.obu.status.boat_suffix"));
                } else {
                    settingComp = settingComp.append(plugin.getMessageManager().getComponent("commands.obu.status.suffix"));
                }
            }
            audience.sendMessage(settingComp);
        }
    }
}
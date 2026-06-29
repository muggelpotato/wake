package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OBUStatusCommand {

    public static CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-status")
                .withModule(OBUModule.class)
                .executesPlayer((ctx, player) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String activeSandbox = service.getPlayerActiveSandbox(player);
                    String contextName = service.getActiveContextName(player);
                    boolean inBoat = player.getVehicle() instanceof Boat;
                    plugin.getMessageManager().send(player, "commands.obu.status.player");
                    String playerBaseName = activeSandbox != null ? activeSandbox : contextName;
                    Boat targetedBoat = null;
                    if (inBoat) {
                        targetedBoat = (Boat) player.getVehicle();
                    } else {
                        Entity targetEntity = player.getTargetEntity(16);
                        if (targetEntity instanceof Boat boat) {
                            targetedBoat = boat;
                        }
                    }

                    Set<String> boatOverriddenKeys = new HashSet<>();
                    OBUContext boatBase = null;
                    Map<String, OBUSetting> boatOverrides = new HashMap<>();
                    String boatContextStr;

                    if (targetedBoat != null) {
                        NamespacedKey key = new NamespacedKey(plugin, "obu_context");
                        boatContextStr = targetedBoat.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                        if (boatContextStr != null) {
                            boatBase = contextManager.getContext(boatContextStr);
                        }
                        boatOverrides = service.getSyncManager().getLocalOverrides(targetedBoat.getUniqueId());
                        boatOverriddenKeys.addAll(boatOverrides.keySet());
                    }

                    // player context
                    OBUContext playerBase = contextManager.getContext(playerBaseName);
                    Map<String, OBUSetting> playerOverrides = service.getSyncManager().getLocalOverrides(player.getUniqueId());
                    
                    if (playerBase == null && playerOverrides.isEmpty()) {
                        plugin.getMessageManager().send(player, "commands.obu.status.empty");
                    } else {
                        if (playerBase != null) {
                            OBUContext defaultContext = contextManager.getContext("default");
                            List<OBUSetting> customSettings = new ArrayList<>();
                            List<OBUSetting> inheritedDefaultSettings = new ArrayList<>();

                            boolean isSandbox = activeSandbox != null;
                            if (isSandbox || playerBaseName.equalsIgnoreCase("default") || defaultContext == null) {
                                customSettings.addAll(playerBase.getSettings());
                            } else {
                                for (OBUSetting s : playerBase.getSettings()) {
                                    boolean inherited = false;
                                    for (OBUSetting def : defaultContext.getSettings()) {
                                        if (s.definition().id() == def.definition().id() && 
                                            s.definition().channel().equals(def.definition().channel()) &&
                                            String.join(", ", s.args()).equals(String.join(", ", def.args()))) {
                                            inherited = true;
                                            break;
                                        }
                                    }
                                    if (inherited) {
                                        inheritedDefaultSettings.add(s);
                                    } else {
                                        customSettings.add(s);
                                    }
                                }
                            }
                            
                            if (!customSettings.isEmpty() || inheritedDefaultSettings.isEmpty()) {
                                plugin.getMessageManager().send(player, "commands.obu.status.subtitle", Placeholder.parsed("contexts", playerBaseName));
                                if (playerBase.getSettings().isEmpty()) {
                                    plugin.getMessageManager().send(player, "commands.obu.status.empty");
                                } else {
                                    printSettingsList(plugin, player, customSettings, playerOverrides, boatOverriddenKeys);
                                }
                            }
                            
                            if (!inheritedDefaultSettings.isEmpty()) {
                                plugin.getMessageManager().send(player, "commands.obu.status.subtitle", Placeholder.parsed("contexts", "default"));
                                printSettingsList(plugin, player, inheritedDefaultSettings, playerOverrides, boatOverriddenKeys);
                            }
                        }
                        if (!playerOverrides.isEmpty()) {
                            plugin.getMessageManager().send(player, "commands.obu.status.temp");
                            printSettingsList(plugin, player, new ArrayList<>(playerOverrides.values()), null, boatOverriddenKeys);
                        }
                    }

                    // boat context
                    if (targetedBoat != null) {
                        plugin.getMessageManager().send(player, "commands.obu.status.boat");
                        if (boatBase == null && boatOverrides.isEmpty()) {
                            plugin.getMessageManager().send(player, "commands.obu.status.empty");
                        } else {
                            if (boatBase != null) {
                                printSettingsList(plugin, player, boatBase.getSettings(), boatOverrides, Collections.emptySet());
                            }
                            if (!boatOverrides.isEmpty()) {
                                plugin.getMessageManager().send(player, "commands.obu.status.temp");
                                printSettingsList(plugin, player, new ArrayList<>(boatOverrides.values()), null, Collections.emptySet());
                            }
                        }
                    }

                    if (activeSandbox == null && plugin.getConfig().getBoolean("config.show_hints", true)) {
                        plugin.getMessageManager().send(player, "commands.obu.status.hint");
                    }

                    return Command.SINGLE_SUCCESS;
                });
    }

    private static void printSettingsList(Wake plugin, Player player, @NonNull List<OBUSetting> settings, Map<String, OBUSetting> overrides, Set<String> boatOverriddenKeys) {
        for (OBUSetting setting : settings) {
            boolean isOverriddenByTemp = overrides != null && overrides.containsKey(setting.getUniqueKey());
            boolean isOverriddenByBoat = boatOverriddenKeys != null && boatOverriddenKeys.contains(setting.getUniqueKey());
            boolean isOverridden = isOverriddenByBoat || isOverriddenByTemp;
            
            Component settingComp = plugin.getMessageManager().getComponent(
                    isOverridden ? "commands.obu.status.overridden" : "commands.obu.status.line",
                    Placeholder.parsed("name", setting.definition().name()),
                    Placeholder.parsed("value", String.join(", ", setting.args())));
            
            if (isOverridden) {
                if (isOverriddenByBoat) {
                    settingComp = settingComp.append(plugin.getMessageManager().getComponent("commands.obu.status.boat_suffix"));
                } else {
                    settingComp = settingComp.append(plugin.getMessageManager().getComponent("commands.obu.status.suffix"));
                }
            }
            player.sendMessage(settingComp);
        }
    }
}
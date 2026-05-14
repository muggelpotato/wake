package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.OBUProtocol;
import dev.muggel.wake.obu.config.OBUConfigManager;
import dev.muggel.wake.obu.networking.PacketSender;
import dev.muggel.wake.core.commands.BaseCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class OBUCommands extends BaseCommand {
    private final OBUProtocol.Definition definition;
    private final PacketSender packetSender;
    private final OBUConfigManager configManager;

    public OBUCommands(OBUProtocol.Definition definition, PacketSender packetSender, OBUConfigManager configManager) {
        super(definition.name());
        this.definition = definition;
        this.packetSender = packetSender;
        this.configManager = configManager;
        this.setPermission(definition.getPermission());
        this.setDescription("OpenBoatUtils " + definition.channel() + " configuration for " + definition.name());
        
        StringBuilder usageBuilder = new StringBuilder("/").append(definition.name());
        for (String type : definition.types()) {
            usageBuilder.append(" <").append(type).append(">");
        }
        this.setUsage(usageBuilder.toString());
        this.setPlayerOnly(true);
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        Player player = (Player) sender;

        if (args.length != definition.types().size()) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Usage: " + this.getUsage(), WakeColors.ERROR)));
            return true;
        }

        try {
            packetSender.sendDynamicPacket(player, definition.channel(), definition.id(), definition.types(), args);

            // default flags notice on reset
            if (definition.id() == 0 && definition.channel().equals("settings")) {
                List<String> appliedSettings = configManager.applyProfile(player, "default");
                player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                        .append(Component.text("Applied Server Defaults:", WakeColors.NEUTRAL)));

                if (appliedSettings.isEmpty()) {
                    player.sendMessage(Component.text("  No settings configured", WakeColors.NEUTRAL));
                } else {
                    for (String applied : appliedSettings) {
                        String[] split = applied.split(": ", 2);
                        if (split.length == 2) {
                            player.sendMessage(Component.text("  - ", WakeColors.NEUTRAL)
                                    .append(Component.text(split[0], WakeColors.ACCENT))
                                    .append(Component.text(": ", WakeColors.NEUTRAL))
                                    .append(Component.text(split[1], WakeColors.PRIMARY)));
                        } else {
                            player.sendMessage(Component.text("  - ", WakeColors.NEUTRAL)
                                    .append(Component.text(applied, WakeColors.ACCENT)));
                        }
                    }
                }
            } else {
                String valueStr = String.join(" ", args);
                Component successMsg = Component.text("[Wake] ", WakeColors.SECONDARY)
                        .append(Component.text("Set ", WakeColors.NEUTRAL))
                        .append(Component.text(commandLabel, WakeColors.ACCENT))
                        .append(Component.text(" to ", WakeColors.NEUTRAL))
                        .append(Component.text(valueStr, WakeColors.PRIMARY));
                player.sendMessage(successMsg);
            }
        } catch (Exception e) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Invalid data format. Expected types: " + definition.types(), WakeColors.ERROR)));
        }
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length <= definition.types().size()) {
            String expectedType = definition.types().get(args.length - 1).toLowerCase();
            String currentArg = args[args.length - 1];

            return switch (expectedType) {
                case "block_list" -> SmartCompleter.registry(currentArg, Registry.MATERIAL);
                case "entity_list" -> SmartCompleter.registry(currentArg, Registry.ENTITY_TYPE);
                case "boolean" -> SmartCompleter.filter(currentArg, SmartCompleter.BOOLEAN);
                case "collision_enum" -> SmartCompleter.filter(currentArg, List.of("VANILLA", "NO_BOATS_OR_PLAYERS", "NO_ENTITIES", "ENTITYTYPE_FILTER", "NO_BOATS_OR_PLAYERS_PLUS_FILTER"));
                case "setting_enum" -> SmartCompleter.filter(currentArg, List.of("JUMP_FORCE", "FORWARDS_ACCEL", "BACKWARDS_ACCEL", "YAW_ACCEL", "TURN_FORWARDS_ACCEL", "WALLTAP_MULTIPLIER", "JUMPS", "COYOTE_TIME"));
                case "context_id" -> List.of("<namespace:context_name>");
                default -> List.of("<" + expectedType + ">");
            };
        }
        return Collections.emptyList();
    }
}

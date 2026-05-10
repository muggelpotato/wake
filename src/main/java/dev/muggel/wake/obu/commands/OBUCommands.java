package dev.muggel.wake.obu.commands;

import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.obu.config.OBUConfigManager;
import dev.muggel.wake.obu.networking.PacketSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class OBUCommands extends Command {
    private final int packetId;
    private final String channel;
    private final List<String> expectedTypes;
    private final PacketSender packetSender;
    private final OBUConfigManager configManager;


    public OBUCommands(String name, int packetId, String channel, List<String> expectedTypes, PacketSender packetSender, OBUConfigManager configManager) {
        super(name);
        this.packetId = packetId;
        this.channel = channel;
        this.expectedTypes = expectedTypes;
        this.packetSender = packetSender;
        this.configManager = configManager;
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("OpenBoatUtils " + channel + " configuration for " + name);
        StringBuilder usageBuilder = new StringBuilder("/").append(name);
        for (String type : expectedTypes) {
            usageBuilder.append(" <").append(type).append(">");
        }
        this.setUsage(usageBuilder.toString());
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NonNull [] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission(OBUManager.OBU_PERMISSION)) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length != expectedTypes.size()) {
            player.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
            return true;
        }

        try {
            packetSender.sendDynamicPacket(player, channel, packetId, expectedTypes, args);

            // default flags notice on reset
            if (packetId == 0 && channel.equals("settings")) {
                List<String> appliedSettings = configManager.applyProfile(player, "default");
                player.sendMessage(Component.text("[Wake] ", NamedTextColor.YELLOW).append(Component.text("Applied Server Defaults: ", NamedTextColor.WHITE)));

                if (appliedSettings.isEmpty()) {
                    player.sendMessage(Component.text("   No default settings configured", NamedTextColor.GRAY));
                } else {
                    for (String applied : appliedSettings) {
                        player.sendMessage(Component.text("  - ", NamedTextColor.GRAY).append(Component.text(applied, NamedTextColor.AQUA)));
                    }
                }
                player.sendMessage(Component.text("Note: You can manually override them", NamedTextColor.GRAY));
            } else {
                String valueStr = String.join(" ", args);
                Component successMsg = Component.text("Successfully configured ", NamedTextColor.GRAY)
                        .append(Component.text(commandLabel, NamedTextColor.AQUA))
                        .append(Component.text(" to ", NamedTextColor.GRAY))
                        .append(Component.text(valueStr, NamedTextColor.WHITE));
                player.sendMessage(successMsg);
            }
        } catch (Exception e) {
            player.sendMessage(Component.text("Invalid data format. Expected types: " + expectedTypes, NamedTextColor.RED));
            player.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NonNull [] args) throws IllegalArgumentException {
        if (args.length <= expectedTypes.size()) {
            String expectedType = expectedTypes.get(args.length - 1).toLowerCase();

            if  (expectedType.equals("block_list")) {
                return getSmartRegistrySuggestions(args[args.length - 1], Registry.MATERIAL);
            } else if (expectedType.equals("entity_list")) {
                return getSmartRegistrySuggestions(args[args.length - 1], Registry.ENTITY_TYPE);
            }
            List<String> suggestions = switch (expectedType) {
                case "boolean" -> List.of("true", "false");
                case "collision_enum" -> List.of("VANILLA", "NO_BOATS_OR_PLAYERS", "NO_ENTITIES", "ENTITYTYPE_FILTER", "NO_BOATS_OR_PLAYERS_PLUS_FILTER");
                case "setting_enum" -> List.of("JUMP_FORCE", "FORWARDS_ACCEL", "BACKWARDS_ACCEL", "YAW_ACCEL", "TURN_FORWARDS_ACCEL", "WALLTAP_MULTIPLIER", "JUMPS", "COYOTE_TIME");
                case "context_id" -> List.of("<namespace:context_name>");
                default -> List.of("<" + expectedType + ">");
            };

            String currentArg = args[args.length - 1].toLowerCase();
            return suggestions.stream()
                    .filter(s -> s.startsWith("<") || s.toLowerCase().startsWith(currentArg))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<String> getSmartRegistrySuggestions(String currentInput, Registry<? extends Keyed> registry) {
        String current = currentInput.toLowerCase();
        String prefix = "";
        String search = current;

        if (current.contains(",")) {
            int lastComma = current.lastIndexOf(",");
            prefix = current.substring(0, lastComma + 1);
            search = current.substring(lastComma + 1);
        }

        List<String> suggestions = new ArrayList<>();

        for (Keyed item : registry) {
            if (item instanceof Material mat && !mat.isBlock()) continue;
            String key = item.getKey().toString();
            String justName = item.getKey().getKey();

            if (key.startsWith(search) || justName.startsWith(search)) {
                    suggestions.add(prefix + key);
                }

        }
        return suggestions;
    }
}
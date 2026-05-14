package dev.muggel.wake.obu.commands;

import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.OBUProtocol;
import dev.muggel.wake.obu.config.OBUProfileManager;
import dev.muggel.wake.obu.model.OBUProfile;
import dev.muggel.wake.obu.model.OBUSetting;
import dev.muggel.wake.obu.service.OBUService;
import dev.muggel.wake.core.commands.SubCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class ProtocolSubCommand implements SubCommand {
    private final OBUProtocol.Definition definition;
    private final OBUService obuService;
    private final OBUProfileManager profileManager;

    public ProtocolSubCommand(OBUProtocol.Definition definition, OBUService obuService, OBUProfileManager profileManager) {
        this.definition = definition;
        this.obuService = obuService;
        this.profileManager = profileManager;
    }

    @Override
    public String getName() {
        return definition.name();
    }

    @Override
    public String getPermission() {
        return definition.getPermission();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be executed by players.", WakeColors.ERROR));
            return;
        }

        if (args.length != definition.types().size()) {
            StringBuilder usage = new StringBuilder("/").append(label).append(" ").append(definition.name());
            for (String type : definition.types()) {
                usage.append(" <").append(type).append(">");
            }
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Usage: " + usage, WakeColors.ERROR)));
            return;
        }

        OBUSetting setting = new OBUSetting(definition, args);
        obuService.applySetting(player, setting);

        // default flags notice and application on reset
        if (definition.id() == 0 && definition.channel().equals("settings")) {
            OBUProfile defaultProfile = profileManager.getProfile("default");
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Applied Server Defaults:", WakeColors.NEUTRAL)));

            if (defaultProfile == null || defaultProfile.isEmpty()) {
                player.sendMessage(Component.text("  No settings configured", WakeColors.NEUTRAL));
            } else {
                obuService.applyProfile(player, defaultProfile);
                for (OBUSetting s : defaultProfile.getSettings()) {
                    player.sendMessage(Component.text("  - ", WakeColors.NEUTRAL)
                            .append(Component.text(s.definition().name(), WakeColors.ACCENT))
                            .append(Component.text(": ", WakeColors.NEUTRAL))
                            .append(Component.text(String.join(", ", s.args()), WakeColors.PRIMARY)));
                }
            }
        } else {
            String valueStr = String.join(" ", args);
            Component successMsg = WakeColors.prefix()
                    .append(Component.text("Set ", WakeColors.NEUTRAL))
                    .append(Component.text(getName(), WakeColors.ACCENT))
                    .append(Component.text(" to ", WakeColors.NEUTRAL))
                    .append(Component.text(valueStr, WakeColors.PRIMARY));
            player.sendMessage(successMsg);
        }
    }

    @Override
    public List<String> suggest(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args.length <= definition.types().size()) {
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

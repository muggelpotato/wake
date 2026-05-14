package dev.muggel.wake.obu.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.defaults.OBUDefaultValue;
import dev.muggel.wake.obu.defaults.OBUDefaults;
import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.obu.networking.PacketSender;
import dev.muggel.wake.core.commands.BaseCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

public class OBUDefaultsCommand extends BaseCommand {

    private final Wake plugin;
    private final PacketSender packetSender;

    public OBUDefaultsCommand(Wake plugin, PacketSender packetSender) {
        super("obudefaults");
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Queries or applies the vanilla default for an OBU setting");
        this.setUsage("/obudefaults <setting> [apply]");
        this.setPlayerOnly(true);
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
        Player player = (Player) sender;

        if (args.length == 0 || args.length > 2) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Usage: " + this.getUsage(), WakeColors.ERROR)));
            return true;
        }

        String setting = args[0].toLowerCase();
        Optional<OBUDefaultValue> defaultValue = OBUDefaults.get(setting);

        if (defaultValue.isEmpty()) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("No default exists for: " + setting, WakeColors.ERROR)));
            return true;
        }

        OBUDefaultValue def = defaultValue.get();
        if (args.length == 1) {
            Component queryMsg = Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Vanilla default for ", WakeColors.NEUTRAL))
                    .append(Component.text(setting, WakeColors.ACCENT))
                    .append(Component.text(" is ", WakeColors.NEUTRAL))
                    .append(Component.text(def.getValueString(), WakeColors.PRIMARY))
                    .append(Component.text(" [Click to revert]", WakeColors.SECONDARY)
                            .clickEvent(ClickEvent.runCommand("/obudefaults " + setting + " apply"))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to revert " + setting + " to default!", WakeColors.ACCENT))));

            player.sendMessage(queryMsg);
            return true;
        }

        if (!args[1].equalsIgnoreCase("apply")) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Usage: " + this.getUsage(), WakeColors.ERROR)));
            return true;
        }

        var protocolDef = dev.muggel.wake.obu.OBUProtocol.get(setting);
        if (protocolDef == null) {
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Setting not found in protocol: " + setting, WakeColors.ERROR)));
            return true;
        }

        try {
            packetSender.sendDynamicPacket(player, protocolDef.channel(), protocolDef.id(), protocolDef.types(), def.values());

            Component successMsg = Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Reverted ", WakeColors.NEUTRAL))
                    .append(Component.text(setting, WakeColors.ACCENT))
                    .append(Component.text(" to vanilla default: ", WakeColors.NEUTRAL))
                    .append(Component.text(def.getValueString(), WakeColors.PRIMARY));

            player.sendMessage(successMsg);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply default for " + setting + ": " + e.getMessage());
            player.sendMessage(Component.text("[Wake] ", WakeColors.SECONDARY)
                    .append(Component.text("Internal error applying default.", WakeColors.ERROR)));
        }

        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return SmartCompleter.filter(args[0], OBUDefaults.getNames());
        } else if (args.length == 2) {
            if (OBUDefaults.get(args[0]).isPresent()) {
                return SmartCompleter.filter(args[1], List.of("apply"));
            }
        }
        return List.of();
    }
}

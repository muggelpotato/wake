package dev.muggel.wake.obu.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.WakeColors;
import dev.muggel.wake.obu.defaults.OBUDefaultValue;
import dev.muggel.wake.obu.defaults.OBUDefaults;
import dev.muggel.wake.obu.OBUModule;
import dev.muggel.wake.obu.networking.PacketSender;
import dev.muggel.wake.core.commands.SubCommand;
import dev.muggel.wake.core.commands.SmartCompleter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

public class OBUDefaultsSubCommand implements SubCommand {

    private final Wake plugin;
    private final PacketSender packetSender;

    public OBUDefaultsSubCommand(Wake plugin, PacketSender packetSender) {
        this.plugin = plugin;
        this.packetSender = packetSender;
    }

    @Override
    public String getName() {
        return "defaults";
    }

    @Override
    public String getPermission() {
        return OBUModule.OBU_PERMISSION;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be executed by players.", WakeColors.ERROR));
            return;
        }

        if (args.length == 0 || args.length > 2) {
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Usage: /" + label + " defaults <setting> [apply]", WakeColors.ERROR)));
            return;
        }

        String setting = args[0].toLowerCase();
        Optional<OBUDefaultValue> defaultValue = OBUDefaults.get(setting);

        if (defaultValue.isEmpty()) {
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("No default exists for: " + setting, WakeColors.ERROR)));
            return;
        }

        OBUDefaultValue def = defaultValue.get();
        if (args.length == 1) {
            Component queryMsg = WakeColors.prefix()
                    .append(Component.text("Vanilla default for ", WakeColors.NEUTRAL))
                    .append(Component.text(setting, WakeColors.ACCENT))
                    .append(Component.text(" is ", WakeColors.NEUTRAL))
                    .append(Component.text(def.getValueString(), WakeColors.PRIMARY))
                    .append(Component.text(" [Click to revert]", WakeColors.SECONDARY)
                            .clickEvent(ClickEvent.runCommand("/" + label + " defaults " + setting + " apply"))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to revert " + setting + " to default!", WakeColors.ACCENT))));

            player.sendMessage(queryMsg);
            return;
        }

        if (!args[1].equalsIgnoreCase("apply")) {
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Usage: /" + label + " defaults <setting> [apply]", WakeColors.ERROR)));
            return;
        }

        var protocolDef = dev.muggel.wake.obu.OBUProtocol.get(setting);
        if (protocolDef == null) {
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Setting not found in protocol: " + setting, WakeColors.ERROR)));
            return;
        }

        try {
            packetSender.sendDynamicPacket(player, protocolDef.channel(), protocolDef.id(), protocolDef.types(), def.values());

            Component successMsg = WakeColors.prefix()
                    .append(Component.text("Reverted ", WakeColors.NEUTRAL))
                    .append(Component.text(setting, WakeColors.ACCENT))
                    .append(Component.text(" to vanilla default: ", WakeColors.NEUTRAL))
                    .append(Component.text(def.getValueString(), WakeColors.PRIMARY));

            player.sendMessage(successMsg);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply default for " + setting + ": " + e.getMessage());
            player.sendMessage(WakeColors.prefix()
                    .append(Component.text("Internal error applying default.", WakeColors.ERROR)));
        }
    }

    @Override
    public List<String> suggest(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            return SmartCompleter.filter(args[0], OBUDefaults.getNames());
        } else if (args.length == 2) {
            if (OBUDefaults.get(args[0].toLowerCase()).isPresent()) {
                return SmartCompleter.filter(args[1], List.of("apply"));
            }
        }
        return List.of();
    }
}

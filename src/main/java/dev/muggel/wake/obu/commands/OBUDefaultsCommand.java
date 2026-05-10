package dev.muggel.wake.obu.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.OBUManager;
import dev.muggel.wake.obu.networking.PacketSender;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OBUDefaultsCommand extends Command {

    private final Wake plugin;
    private final PacketSender packetSender;
    private final Map<String, String[]> defaults = new HashMap<>();

    public OBUDefaultsCommand(Wake plugin, PacketSender packetSender) {
        super("obudefaults");
        this.plugin = plugin;
        this.packetSender = packetSender;
        this.setPermission(OBUManager.OBU_PERMISSION);
        this.setDescription("Queries or applies the vanilla default for an OBU setting");
        this.setUsage("/obudefaults <setting> [apply]");

        defaults.put("falldamage", new String[]{"true"});
        defaults.put("waterelevation", new String[]{"false"});
        defaults.put("aircontrol", new String[]{"false"});
        defaults.put("defaultslipperiness", new String[]{"0.6"});
        defaults.put("jumpforce", new String[]{"0.0"});
        defaults.put("stepsize", new String[]{"0.0"});
        defaults.put("boatgravity", new String[]{"-0.03999999910593033"});
        defaults.put("setyawaccel", new String[]{"1.0"});
        defaults.put("setforwardaccel", new String[]{"0.04"});
        defaults.put("setbackwardaccel", new String[]{"0.005"});
        defaults.put("setturnforwardaccel", new String[]{"0.005"});
        defaults.put("allowaccelstacking", new String[]{"false"});
        defaults.put("underwatercontrol", new String[]{"false"});
        defaults.put("surfacewatercontrol", new String[]{"false"});
        defaults.put("coyotetime", new String[]{"0"});
        defaults.put("waterjumping", new String[]{"false"});
        defaults.put("swimforce", new String[]{"0.0"});
        defaults.put("collisionmode", new String[]{"VANILLA"});
        defaults.put("stepwhilefalling", new String[]{"false"});
        defaults.put("setcollisionresolution", new String[]{"1"});
        defaults.put("setwalltapmultiplier", new String[]{"0.0"});
        defaults.put("setjumps", new String[]{"1"});
        defaults.put("setscale", new String[]{"1.0"});
        defaults.put("setstepupslipperiness", new String[]{"1.0"});
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String @NonNull [] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission(OBUManager.OBU_PERMISSION)) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args.length > 2) {
            player.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
            return true;
        }

        String setting = args[0].toLowerCase();

        if (!defaults.containsKey(setting)) {
            player.sendMessage(Component.text("No default exists for: " + setting, NamedTextColor.RED));
            return true;
        }

        String[] defaultArgs = defaults.get(setting);
        String valueStr = String.join(" ", defaultArgs);
        if (args.length == 1) {
            Component queryMsg = Component.text("The vanilla default for ", NamedTextColor.GRAY)
                    .append(Component.text(setting, NamedTextColor.AQUA))
                    .append(Component.text(" is ", NamedTextColor.GRAY))
                    .append(Component.text(valueStr, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(" [Click to apply]", NamedTextColor.AQUA))
                            .clickEvent(ClickEvent.runCommand("/obudefaults " + setting + " apply"))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to revert " + setting + " to default!")));

            player.sendMessage(queryMsg);
            return true;
        }

        if (!args[1].equalsIgnoreCase("apply")) {
            player.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
            return true;
        }

        ConfigurationSection cmdDef = plugin.getConfig().getConfigurationSection("obu.commands." + setting);
        if (cmdDef == null) {
            player.sendMessage(Component.text("Setting not found in config.yml: " + setting, NamedTextColor.RED));
            return true;
        }

        int id = cmdDef.getInt("id");
        String channel = cmdDef.getString("channel", "settings");
        List<String> types = cmdDef.getStringList("types");

        try {
            packetSender.sendDynamicPacket(player, channel, id, types, defaultArgs);

            // Success message using your custom Hex colors
            Component successMsg = Component.text("Reverted ", NamedTextColor.GRAY)
                    .append(Component.text(setting, NamedTextColor.AQUA))
                    .append(Component.text(" to vanilla default: ", NamedTextColor.GRAY))
                    .append(Component.text(valueStr, NamedTextColor.WHITE));

            player.sendMessage(successMsg);

        } catch (Exception e) {
            player.sendMessage(Component.text("Failed to parse internal default value for: " + setting, NamedTextColor.RED));
        }

        return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String @NonNull [] args) {
        // Only suggest commands that exist in our hardcoded defaults map!
        if (args.length == 1) {
            String currentArg = args[0].toLowerCase();
            return defaults.keySet().stream()
                    .filter(key -> key.startsWith(currentArg))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            String setting = args[0].toLowerCase();
            if (defaults.containsKey(setting) && "apply".startsWith(args[1].toLowerCase())) {
                return List.of("apply");
            }
        }

        return Collections.emptyList();
    }
}
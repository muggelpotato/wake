package dev.muggel.wake.obu;

import com.github.retrooper.packetevents.PacketEvents;
import dev.muggel.wake.Wake;
import dev.muggel.wake.obu.commands.OBUCommands;
import dev.muggel.wake.obu.config.OBUConfigManager;
import dev.muggel.wake.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.obu.networking.HandshakeListener;
import dev.muggel.wake.obu.networking.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Objects;

public class OBUManager {
    private final PacketSender packetSender;
    public static final String OBU_PERMISSION = "wake.obu.commands";
    public OBUManager(Wake plugin) {
        plugin.getLogger().info("Initializing OpenBoatUtils Feature");

        this.packetSender = new PacketSender(plugin);
        OBUConfigManager configManager = new OBUConfigManager(plugin, packetSender);
        new HandshakeListener(plugin, packetSender, configManager);

        PacketEvents.getAPI().getEventManager().registerListener(new BoatLagInterceptor(plugin));


        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        ConfigurationSection commands = plugin.getConfig().getConfigurationSection("obu.commands");

        commandMap.register("wakeobu", new dev.muggel.wake.obu.commands.OBUHelpCommand(plugin));
        commandMap.register("wakeobu", new dev.muggel.wake.obu.commands.OBUDefaultsCommand(plugin, packetSender));

        if (commands != null) {
            for (String cmdName : commands.getKeys(false)) {
                int id = commands.getInt(cmdName + ".id");
                String channel = commands.getString(cmdName + ".channel", "settings");
                List<String> types = commands.getStringList(cmdName + ".types");

                // Registers native commands with the fallback prefix (e.g., /wakeobu:stepsize)
                commandMap.register("wakeobu", new OBUCommands(cmdName, id, channel, types, packetSender, configManager));
            }
        }

        plugin.getLogger().info("OBU Module successfully loaded!");
    }

    public PacketSender getPacketSender() { return packetSender; }
}
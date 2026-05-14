package dev.muggel.wake.obu;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.WakeModule;
import dev.muggel.wake.obu.commands.OBUCommands;
import dev.muggel.wake.obu.commands.OBUDefaultsCommand;
import dev.muggel.wake.obu.commands.OBUHelpCommand;
import dev.muggel.wake.obu.commands.OBUProfileCommand;
import dev.muggel.wake.obu.config.OBUConfigManager;
import dev.muggel.wake.obu.networking.interceptors.BoatLagInterceptor;
import dev.muggel.wake.obu.networking.HandshakeListener;
import dev.muggel.wake.obu.networking.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;

public class OBUManager implements WakeModule {
    public static final String OBU_PERMISSION = "wake.obu.commands";
    private OBUConfigManager configManager;

    @Override
    public String getId() { return "obu"; }

    @Override
    public void onEnable(Wake plugin) {
        plugin.getLogger().info("Initializing OpenBoatUtils Module");

        PacketSender packetSender = new PacketSender();
        this.configManager = new OBUConfigManager(plugin, packetSender);
        new HandshakeListener(plugin, configManager);

        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(new BoatLagInterceptor());

        // register all commands of the obu module
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        
        OBUHelpCommand helpCmd = new OBUHelpCommand();
        helpCmd.setParentPermission(OBU_PERMISSION);
        commandMap.register("wakeobu", helpCmd);

        OBUDefaultsCommand defaultsCmd = new OBUDefaultsCommand(plugin, packetSender);
        defaultsCmd.setParentPermission(OBU_PERMISSION);
        commandMap.register("wakeobu", defaultsCmd);

        OBUProfileCommand profileCmd = new OBUProfileCommand(plugin, configManager);
        profileCmd.setParentPermission(OBU_PERMISSION);
        commandMap.register("wakeobu", profileCmd);

        for (String cmdName : OBUProtocol.getRegisteredNames()) {
            OBUProtocol.Definition def = OBUProtocol.get(cmdName);
            OBUCommands cmd = new OBUCommands(def, packetSender, configManager);
            cmd.setParentPermission(OBU_PERMISSION);
            commandMap.register("wakeobu", cmd);
        }
    }

    public OBUConfigManager getConfigManager() {
        return configManager;
    }
}
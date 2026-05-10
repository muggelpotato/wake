package dev.muggel.wake;

import com.github.retrooper.packetevents.PacketEvents;
import dev.muggel.wake.commands.WakeCommand;
import dev.muggel.wake.listeners.BoatListener;
import dev.muggel.wake.obu.OBUManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Wake extends JavaPlugin {
    private OBUManager obuManager;
    private boolean killBoatOnExit;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        PacketEvents.getAPI().init();
        saveDefaultConfig();
        reloadSettings();
        Bukkit.getServer().getCommandMap().register("wake", new WakeCommand(this));
        Bukkit.getPluginManager().registerEvents(new BoatListener(this), this);

        if (getConfig().getBoolean("wake.modules.obu", true)) {
            this.obuManager = new OBUManager(this);
        } else {
            getLogger().info("OBU Module is disabled in config.yml.");
        }

        getLogger().info("wake has been enabled");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PacketEvents.getAPI().terminate();
        getLogger().info("wake has been disabled");
    }

    // temporary
    public void reloadSettings() {
        reloadConfig();
        this.killBoatOnExit = getConfig().getBoolean("wake.config.killboatonexit", false);
    }
    public boolean isKillBoatOnExit() { return killBoatOnExit; }
    public void setKillBoatOnExit(boolean killBoatOnExit) { this.killBoatOnExit = killBoatOnExit; } // non persistent

    public OBUManager getObuManager() {
        return obuManager;
    }
}

package dev.muggel.wake;

import com.github.retrooper.packetevents.PacketEvents;
import dev.muggel.wake.obu.OBUManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

public final class Wake extends JavaPlugin {

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
        new OBUManager(this);



        getLogger().info("wake has been enabled");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        PacketEvents.getAPI().terminate();
        getLogger().info("wake has been disabled");
    }
}

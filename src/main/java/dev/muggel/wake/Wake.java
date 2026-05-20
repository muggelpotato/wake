package dev.muggel.wake;

import dev.muggel.wake.core.Module;
import dev.muggel.wake.core.ModuleManager;
import dev.muggel.wake.core.WakeModule;
import dev.muggel.wake.core.commands.WakeCommand;
import dev.muggel.wake.obu.OBUModule;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class Wake extends JavaPlugin {
    private ModuleManager moduleManager;
    private WakeCommand wakeCommand;

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.moduleManager = new ModuleManager(this);
        registerModules();
        moduleManager.syncModules(Bukkit.getConsoleSender());

        wakeCommand = new WakeCommand(this);
        Bukkit.getServer().getCommandMap().register("wake", wakeCommand);

        PacketEvents.getAPI().init();

        getLogger().info("wake has been enabled");
    }

    private void registerModules() {
        moduleManager.registerModule(new WakeModule());
        moduleManager.registerModule(new OBUModule());
    }

    @Override
    public void onDisable() {
        if (wakeCommand != null) {
            wakeCommand.unregister(Bukkit.getServer().getCommandMap());
            wakeCommand = null;
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        PacketEvents.getAPI().terminate();
        getLogger().info("wake has been disabled");
    }

    public void reloadSettings(CommandSender sender) {
        reloadConfig();
        if (moduleManager != null) {
            moduleManager.syncModules(sender);
        }

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        return moduleManager != null ? moduleManager.getModule(clazz) : null;
    }
}

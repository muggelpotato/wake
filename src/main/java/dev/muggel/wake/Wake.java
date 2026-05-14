package dev.muggel.wake;

import dev.muggel.wake.core.Module;
import dev.muggel.wake.core.ModuleManager;
import dev.muggel.wake.core.WakeModule;
import dev.muggel.wake.core.commands.WakeCommand;
import dev.muggel.wake.obu.OBUModule;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class Wake extends JavaPlugin {
    private ModuleManager moduleManager;
    private WakeCommand wakeCommand;

    @Override
    public void onEnable() {
        initPacketEvents();
        saveDefaultConfig();

        this.moduleManager = new ModuleManager(this);
        registerModules();
        moduleManager.syncModules(Bukkit.getConsoleSender());

        wakeCommand = new WakeCommand(this);
        Bukkit.getServer().getCommandMap().register("wake", wakeCommand);

        getLogger().info("wake has been enabled");
    }

    private void registerModules() {
        moduleManager.registerModule(new WakeModule());
        moduleManager.registerModule(new OBUModule());
    }

    private void initPacketEvents() {
        com.github.retrooper.packetevents.PacketEvents.setAPI(io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder.build(this));
        com.github.retrooper.packetevents.PacketEvents.getAPI().load();
        com.github.retrooper.packetevents.PacketEvents.getAPI().init();
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
        com.github.retrooper.packetevents.PacketEvents.getAPI().terminate();
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

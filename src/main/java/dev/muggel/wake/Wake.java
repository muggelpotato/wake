package dev.muggel.wake;

import dev.muggel.wake.core.WakeModule;
import dev.muggel.wake.core.GeneralModule;
import dev.muggel.wake.core.commands.WakeCommand;
import dev.muggel.wake.obu.OBUManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class Wake extends JavaPlugin {
    private final List<WakeModule> modules = new ArrayList<>();

    @Override
    public void onLoad() {
        initPacketEvents();
    }

    @Override
    public void onEnable() {
        com.github.retrooper.packetevents.PacketEvents.getAPI().init();

        saveDefaultConfig();
        reloadSettings();

        Bukkit.getServer().getCommandMap().register("wake", new WakeCommand(this));

        // Register Modules
        registerModule(new GeneralModule());
        registerModule(new OBUManager());

        getLogger().info("wake has been enabled");
    }

    private void initPacketEvents() {
        com.github.retrooper.packetevents.PacketEvents.setAPI(io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder.build(this));
        com.github.retrooper.packetevents.PacketEvents.getAPI().load();
    }

    private void registerModule(WakeModule module) {
        if (getConfig().getBoolean("wake.modules." + module.getId(), true)) {
            module.onEnable(this);
            modules.add(module);
        } else {
            getLogger().info("Module '" + module.getId() + "' is disabled in config.yml.");
        }
    }

    @Override
    public void onDisable() {
        for (WakeModule module : modules) {
            module.onDisable(this);
        }
        com.github.retrooper.packetevents.PacketEvents.getAPI().terminate();
        getLogger().info("wake has been disabled");
    }

    public void reloadSettings() {
        reloadConfig();
        for (WakeModule module : modules) {
            if (module instanceof GeneralModule gm) {
                gm.reload(this);
            }
        }
    }

    public <T extends WakeModule> T getModule(Class<T> clazz) {
        return modules.stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .findFirst()
                .orElse(null);
    }
}

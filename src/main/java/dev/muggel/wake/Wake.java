package dev.muggel.wake;

import dev.muggel.wake.core.config.StateManager;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.core.module.ModuleManager;
import dev.muggel.wake.core.module.WakeModule;
import dev.muggel.wake.core.registry.ServiceRegistry;
import dev.muggel.wake.features.drydock.DrydockModule;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.base.BaseModule;
import dev.muggel.wake.features.base.commands.WakeCommandRegistry;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Collections;

public final class Wake extends JavaPlugin {
    private static ServiceRegistry serviceRegistry;
    private ModuleManager moduleManager;
    private StateManager stateManager;
    private MessageManager messageManager;
    public static ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    @Override
    public void onEnable() {
        initPacketEvents();
        saveDefaultConfig();
        this.stateManager = new StateManager(this);
        this.messageManager = new MessageManager(this);
        serviceRegistry = new ServiceRegistry();
        
        new WakeCommandRegistry(this).register();
        
        this.moduleManager = new ModuleManager(this);
        registerModules();
        moduleManager.syncModules();
        getLogger().info("Wake has been enabled");
    }

    private void registerModules() {
        moduleManager.registerModule(new BaseModule());
        moduleManager.registerModule(new OBUModule());
        moduleManager.registerModule(new DrydockModule());
    }

    private void initPacketEvents() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        PacketEvents.getAPI().init();
    }

    @Override
    public void onDisable() {
        try {
            if (stateManager != null) {
                stateManager.saveSync();
            }
            if (moduleManager != null) {
                moduleManager.disableAll();
                moduleManager = null;
            }
        } finally {
            try {
                if (serviceRegistry != null) {
                    serviceRegistry.unregisterAll();
                    serviceRegistry = null;
                }
            } finally {
                PacketEvents.getAPI().terminate();
                getLogger().info("Wake has been disabled");
            }
        }
    }

    public List<Component> reloadSettings(CommandSender sender) {
        reloadConfig();
        if (messageManager != null) {
            messageManager.reload();
        }
        List<Component> feedback = Collections.emptyList();
        if (moduleManager != null) {
            feedback = moduleManager.syncModules();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
        return feedback;
    }

    public <T extends WakeModule> @Nullable T getModule(Class<T> clazz) {
        return moduleManager != null ? moduleManager.getModule(clazz) : null;
    }

    public StateManager getStateManager() {
        return stateManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }
}

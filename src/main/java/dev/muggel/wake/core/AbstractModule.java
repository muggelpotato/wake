package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandRegistry;
import dev.muggel.wake.core.commands.BaseCommand;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractModule implements Module {
    private final String id;
    private Wake plugin;
    private CommandRegistry commandRegistry;
    private final List<Listener> bukkitListeners = new ArrayList<>();
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();

    protected AbstractModule(String id) {
        this.id = id;
    }

    @Override
    public String getId() { return id; }

    @Override
    public final void onEnable(Wake plugin) {
        this.plugin = plugin;
        this.commandRegistry = new CommandRegistry(plugin);
        onModuleEnable(plugin);
    }

    public Wake getPlugin() {
        return plugin;
    }

    @Override
    public final void onDisable(Wake plugin) {
        onModuleDisable(plugin);
        if (commandRegistry != null) {
            commandRegistry.unregisterAll();
            commandRegistry = null;
        }
        for (Listener listener : bukkitListeners) {
            HandlerList.unregisterAll(listener);
        }
        bukkitListeners.clear();
        for (PacketListenerCommon listener : packetListeners) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
        packetListeners.clear();
    }
    
    @Override
    public void reload(Wake plugin) {}

    protected abstract void onModuleEnable(Wake plugin);
    protected void onModuleDisable(Wake plugin) {}

    protected void registerCommand(String prefix, BaseCommand command) {
        if (commandRegistry != null) {
            commandRegistry.register(prefix, command);
        }
    }

    protected void registerListener(Wake plugin, Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        bukkitListeners.add(listener);
    }

    protected void registerPacketListener(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        packetListeners.add(listener);
    }
}

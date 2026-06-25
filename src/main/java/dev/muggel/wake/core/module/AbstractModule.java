package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractModule implements WakeModule {
    private final String id;
    protected Wake plugin;
    private final List<Listener> bukkitListeners = new ArrayList<>();
    private final List<PacketListenerCommon> packetListeners = new ArrayList<>();

    protected AbstractModule(String id) {
        this.id = id;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final void onEnable(Wake plugin) {
        this.plugin = plugin;
        onModuleEnable();
    }

    @Override
    public final void onDisable() {
        try {
            onModuleDisable();
        } finally {
            for (Listener listener : bukkitListeners) {
                HandlerList.unregisterAll(listener);
            }
            bukkitListeners.clear();
            for (PacketListenerCommon listener : packetListeners) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            }
            packetListeners.clear();
        }
    }

    @Override
    public void reload() {}

    public final Wake getPlugin() {
        return plugin;
    }

    protected abstract void onModuleEnable();

    protected void onModuleDisable() {}

    protected final void registerListener(Listener listener) {
        if (plugin == null) {
            throw new IllegalStateException("Cannot register listener before module is enabled");
        }
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        bukkitListeners.add(listener);
    }

    protected final void registerPacketListener(PacketListenerCommon listener) {
        PacketEvents.getAPI().getEventManager().registerListener(listener);
        packetListeners.add(listener);
    }
}

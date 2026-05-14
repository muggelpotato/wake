package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import dev.muggel.wake.listeners.BoatListener;
import org.bukkit.Bukkit;


public class GeneralModule implements WakeModule {
    private Wake plugin;
    private boolean killBoatOnExit;

    @Override
    public String getId() { return "general"; }

    @Override
    public void onEnable(Wake plugin) {
        this.plugin = plugin;
        this.killBoatOnExit = plugin.getConfig().getBoolean("wake.config.killboatonexit", false);
        Bukkit.getPluginManager().registerEvents(new BoatListener(this), plugin);
    }

    public Wake getPlugin() { return plugin; }
    public boolean isKillBoatOnExit() { return killBoatOnExit; }
    public void setKillBoatOnExit(boolean killBoatOnExit) { this.killBoatOnExit = killBoatOnExit; }

    public void reload(Wake plugin) {
        this.killBoatOnExit = plugin.getConfig().getBoolean("wake.config.killboatonexit", false);
    }
}

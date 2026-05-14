package dev.muggel.wake.core;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.listeners.BoatListener;

public class WakeModule extends AbstractModule {
    private boolean killBoatOnExit;

    public WakeModule() {
        super("core");
    }

    @Override
    protected void onModuleEnable(Wake plugin) {
        this.killBoatOnExit = plugin.getConfig().getBoolean("wake.config.killboatonexit", false);
        registerListener(plugin, new BoatListener(this));
    }

    public boolean isKillBoatOnExit() { return killBoatOnExit; }
    public void setKillBoatOnExit(boolean killBoatOnExit) { this.killBoatOnExit = killBoatOnExit; }

    @Override
    public void reload(Wake plugin) {
        this.killBoatOnExit = plugin.getConfig().getBoolean("wake.config.killboatonexit", false);
    }
}

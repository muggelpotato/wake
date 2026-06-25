package dev.muggel.wake.features.base;

import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.base.listeners.BoatListener;

public class BaseModule extends AbstractModule {
    private boolean killBoatOnExit;

    public BaseModule() {
        super("base");
    }

    @Override
    protected void onModuleEnable() {
        this.killBoatOnExit = plugin.getStateManager().get("killboatonexit", false);
        registerListener(new BoatListener(this));
    }

    public boolean isKillBoatOnExit() {
        return killBoatOnExit;
    }

    public void setKillBoatOnExit(boolean killBoatOnExit) {
        this.killBoatOnExit = killBoatOnExit;
        plugin.getStateManager().set("killboatonexit", killBoatOnExit);
    }

    @Override
    public void reload() {
        plugin.getStateManager().load();
        this.killBoatOnExit = plugin.getStateManager().get("killboatonexit", false);
    }
}

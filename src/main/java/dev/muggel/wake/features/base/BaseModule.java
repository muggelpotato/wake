package dev.muggel.wake.features.base;

import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.base.listeners.BoatListener;

public class BaseModule extends AbstractModule {
    public BaseModule() {
        super("base");
    }
 
    @Override
    protected void onModuleEnable() {
        registerListener(new BoatListener(this));
    }
 
    public boolean isKillBoatOnExit() {
        return plugin.getStateManager().get("killboatonexit", false);
    }
 
    @Override
    public void reload() {
        plugin.getStateManager().load();
    }
}

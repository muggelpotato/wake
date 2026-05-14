package dev.muggel.wake.core;

import dev.muggel.wake.Wake;

// represents a toggleable plugin module
public interface Module {
    void onEnable(Wake plugin);
    default void onDisable(Wake plugin) {}
    void reload(Wake plugin);
    String getId();
}

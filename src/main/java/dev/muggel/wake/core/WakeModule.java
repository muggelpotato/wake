package dev.muggel.wake.core;

import dev.muggel.wake.Wake;

// represents a toggleable plugin module
public interface WakeModule {
    void onEnable(Wake plugin);
    default void onDisable(Wake plugin) {}
    String getId();
}

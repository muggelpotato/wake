package dev.muggel.wake.core.module;

import dev.muggel.wake.Wake;

public interface WakeModule {
    void onEnable(Wake plugin);
    void onDisable();
    void reload();
    String getId();
}

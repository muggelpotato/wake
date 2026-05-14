package dev.muggel.wake.obu.model;

import java.util.List;

public record OBUProfile(String name, List<OBUSetting> settings) {
    public List<OBUSetting> getSettings() {
        return settings;
    }

    public boolean isEmpty() {
        return settings.isEmpty();
    }
}

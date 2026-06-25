package dev.muggel.wake.features.obu.context;

import java.util.List;

public record OBUContext(String name, List<OBUSetting> settings) {
    public OBUContext {
        settings = List.copyOf(settings);
    }

    public List<OBUSetting> getSettings() {
        return settings;
    }

    public boolean isEmpty() {
        return settings.isEmpty();
    }
}

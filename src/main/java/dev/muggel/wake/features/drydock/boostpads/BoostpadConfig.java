package dev.muggel.wake.features.drydock.boostpads;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NonNull;

public record BoostpadConfig(
        @NonNull String blockKey,
        boolean enabled,
        double forceX,
        double forceY,
        double forceZ,
        long delayMs,
        double padding
) {
    public static final double MAX_PADDING = 4.0;
    public static final double DEFAULT_PADDING = 1.0;
    public static final long MAX_DELAY_MS = Integer.MAX_VALUE;

    public BoostpadConfig {
        forceX = Double.isFinite(forceX) ? forceX : 0.0;
        forceY = Double.isFinite(forceY) ? forceY : 0.0;
        forceZ = Double.isFinite(forceZ) ? forceZ : 0.0;
        delayMs = Math.clamp(delayMs, 0L, MAX_DELAY_MS);
        padding = Double.isFinite(padding) ? Math.clamp(padding, 0.0, MAX_PADDING) : DEFAULT_PADDING;
    }

    public void writeTo(@NonNull ConfigurationSection section) {
        section.set("enabled", enabled);
        section.set("force_x", forceX);
        section.set("force_y", forceY);
        section.set("force_z", forceZ);
        section.set("delay_ms", delayMs);
        section.set("padding", padding);
    }

    public static @NonNull BoostpadConfig read(@NonNull String blockKey, @NonNull ConfigurationSection section) {
        return new BoostpadConfig(blockKey,
                section.getBoolean("enabled", true),
                section.getDouble("force_x"),
                section.getDouble("force_y"),
                section.getDouble("force_z"),
                section.getLong("delay_ms"),
                section.getDouble("padding", DEFAULT_PADDING));
    }
}
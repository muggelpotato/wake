package dev.muggel.wake.features.drydock.boostpads;

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

    public BoostpadConfig {
        forceX = Double.isFinite(forceX) ? forceX : 0.0;
        forceY = Double.isFinite(forceY) ? forceY : 0.0;
        forceZ = Double.isFinite(forceZ) ? forceZ : 0.0;
        delayMs = Math.max(0L, delayMs);
        padding = Double.isFinite(padding) ? Math.clamp(padding, 0.0, MAX_PADDING) : DEFAULT_PADDING;
    }
}
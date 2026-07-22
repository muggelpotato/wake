package dev.muggel.wake.features.drydock.api;

import org.jspecify.annotations.NonNull;

public record BoostpadConfig(
        @NonNull String blockKey,
        boolean enabled,
        double forceX,
        double forceY,
        double forceZ,
        long delayMs,
        int hitboxPercent
) {
    public BoostpadConfig {
        forceX = Double.isFinite(forceX) ? forceX : 0.0;
        forceY = Double.isFinite(forceY) ? forceY : 0.0;
        forceZ = Double.isFinite(forceZ) ? forceZ : 0.0;
        delayMs = Math.max(0L, delayMs);
        hitboxPercent = Math.clamp(hitboxPercent, 0, 245);
    }

    public double offsetMultiplier() {
        return (hitboxPercent / 100.0) - 1.0;
    }
}
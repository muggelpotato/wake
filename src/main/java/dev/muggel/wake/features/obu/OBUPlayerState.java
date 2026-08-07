package dev.muggel.wake.features.obu;

import org.jspecify.annotations.Nullable;

public record OBUPlayerState(@Nullable String activeSandbox, @Nullable String activeContext) {}
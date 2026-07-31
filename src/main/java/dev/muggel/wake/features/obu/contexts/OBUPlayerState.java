package dev.muggel.wake.features.obu.contexts;

import org.jspecify.annotations.Nullable;

public record OBUPlayerState(@Nullable String activeSandbox, @Nullable String activeContext) {}
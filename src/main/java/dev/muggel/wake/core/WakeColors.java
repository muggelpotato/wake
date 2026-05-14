package dev.muggel.wake.core;

import net.kyori.adventure.text.format.TextColor;

public class WakeColors {
    public static final TextColor PRIMARY = TextColor.color(0x33B5FF);
    public static final TextColor SECONDARY = TextColor.color(0x5C66FF);
    public static final TextColor ACCENT = TextColor.color(0x95A5FF);
    public static final TextColor ERROR = TextColor.color(0xFF5252);
    public static final TextColor NEUTRAL = TextColor.color(0xCCCCCC);

    public static net.kyori.adventure.text.Component prefix() {
        return net.kyori.adventure.text.Component.text("[Wake] ", SECONDARY);
    }
}

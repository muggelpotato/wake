package dev.muggel.wake.core.text;

import net.kyori.adventure.text.format.TextColor;

/**
 * Wake's color palette. <br>
 * Exposed to the language file as semantic MiniMessage tags ({@code <primary>}, {@code <danger>}, ...). <br>
 * Recolor the plugin by editing these constants (never write raw hex in messages).
 */
public class WakeColors {
    public static final TextColor PRIMARY = TextColor.color(0x33B5FF);
    public static final TextColor SECONDARY = TextColor.color(0x5C66FF);
    public static final TextColor ACCENT = TextColor.color(0x95A5FF);
    public static final TextColor ERROR = TextColor.color(0xFF5252);
    public static final TextColor NEUTRAL = TextColor.color(0xCCCCCC);
    public static final TextColor INFO = TextColor.color(0x4DD0E1);
    public static final TextColor MUTED_DARK = TextColor.color(0x666666);
    public static final TextColor OVERRIDDEN = TextColor.color(0xAA55FF);
}
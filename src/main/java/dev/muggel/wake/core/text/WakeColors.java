package dev.muggel.wake.core.text;

import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.TagPattern;
import org.intellij.lang.annotations.Subst;
import org.jspecify.annotations.NonNull;

/**
 * Wake's color palette <br>
 * Recolor the plugin by editing these entries <br>
 * {@link MessageManager} derives both the {@code <tag>} resolver and the {@code $var} expansions from this list (never write raw hex in messages)
 */
public enum WakeColors {
    /** highlights, success values, counts */
    PRIMARY("primary", 0x33B5FF),
    /** accents, [Button] chips */
    SECONDARY("secondary", 0x5C66FF),
    /** names, identifiers, echoed user input */
    ACCENT("accent", 0x95A5FF),
    /** values shadowed by another OBU context */
    OVERRIDDEN("overridden", 0xAA55FF),
    /** hint chips, notices */
    INFO("info", 0x4DD0E1),
    /** errors, destructive results */
    ERROR("danger", 0xFF5252),
    /** body text */
    NEUTRAL("neutral", 0xC9CEE4),
    /** arrows, separators, bullets, » prefix */
    MUTED("muted", 0x8B92BD),
    /** empty states */
    MUTED_DARK("muted_dark", 0x62678C);

    private final @TagPattern String tag;
    private final TextColor color;
    WakeColors(@TagPattern String tag, int rgb) {
        this.tag = tag;
        this.color = TextColor.color(rgb);
    }

    @Subst("primary")
    public @TagPattern String tag() {
        return tag;
    }

    public TextColor color() {
        return color;
    }

    public @NonNull String asHexString() {
        return color.asHexString();
    }
}
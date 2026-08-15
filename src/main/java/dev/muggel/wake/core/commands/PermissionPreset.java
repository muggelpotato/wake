package dev.muggel.wake.core.commands;

/**
 * A bundle of command permissions. <br>
 * A bundle is empty until commands declare themselves part of it with {@link CommandNode#withPreset}, which covers that node and everything below it. <br>
 * {@link CommandNode#withoutPresets()} punches a hole for a sub-command its parent's bundles shouldn't reach. <br>
 * Any permission written by hand in-game outranks them.
 */
public enum PermissionPreset {
    BUILDER("wake.presetperms.builder"),
    PLAYER("wake.presetperms.player");
    private final String node;
    PermissionPreset(String node) {
        this.node = node;
    }

    public String node() {
        return node;
    }
}
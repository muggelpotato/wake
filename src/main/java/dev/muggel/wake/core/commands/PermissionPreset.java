package dev.muggel.wake.core.commands;

/**
 * A bundle of command permissions. <br>
 * A bundle is empty until commands declare themselves part of it with {@link CommandNode#withPreset} (one node) or {@link CommandNode#withPresetBranch} (a whole branch).
 */
public enum PermissionPreset {
    ADMIN("wake.presetperms.admin"),
    PLAYER("wake.presetperms.player");
    private final String node;
    PermissionPreset(String node) {
        this.node = node;
    }

    public String node() {
        return node;
    }
}
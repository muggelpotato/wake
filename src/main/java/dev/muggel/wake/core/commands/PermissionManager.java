package dev.muggel.wake.core.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the permission nodes, most specific rule first. <br>
 * 1. An explicit {@code true} or {@code false} on the command itself <br>
 * 2. The closest parent that says anything <br>
 * 3. A {@link PermissionPreset} naming it (the floor a handwritten permission always outranks) <br>
 * 4. Its default: commands OP, bundles nobody <br>
 * Never construct permission strings elsewhere.
 * This class only ever sees what {@link WakeCommandManager} derives from the command tree.
 */
public final class PermissionManager {
    private static final Map<String, Set<String>> CHILD_NODES = new ConcurrentHashMap<>();
    private static final Map<String, Set<PermissionPreset>> NODE_PRESETS = new ConcurrentHashMap<>();
    private static final Set<String> EXECUTABLE_NODES = ConcurrentHashMap.newKeySet();
    private PermissionManager() {}

    /** Marks a permission as a command rather than a group node (decides how it is reached) */
    static void markExecutable(@NonNull String permissionStr) {
        EXECUTABLE_NODES.add(permissionStr);
    }

    static void assignPresets(@NonNull String permissionStr, @NonNull Set<PermissionPreset> presets) {
        if (presets.isEmpty()) {
            return;
        }
        NODE_PRESETS.computeIfAbsent(permissionStr, ignored -> EnumSet.noneOf(PermissionPreset.class)).addAll(presets);
    }

    /** Settles the bundles once the whole tree is compiled */
    static void sealPresets() {
        NODE_PRESETS.keySet().retainAll(EXECUTABLE_NODES);
        for (Map.Entry<String, Set<PermissionPreset>> entry : NODE_PRESETS.entrySet()) {
            String above = enclosingCommand(entry.getKey());
            if (above == null) {
                continue;
            }
            Set<PermissionPreset> carried = NODE_PRESETS.getOrDefault(above, Set.of());
            for (PermissionPreset preset : entry.getValue()) {
                if (!carried.contains(preset)) {
                    throw new IllegalStateException(entry.getKey() + " is in bundle " + preset + " but the command above it (" + above + ") is not, so the bundle can never reach it");
                }
            }
        }
    }

    private static @Nullable String enclosingCommand(@NonNull String permissionStr) {
        for (int dot = permissionStr.lastIndexOf('.'); dot > 0; dot = permissionStr.lastIndexOf('.', dot - 1)) {
            String candidate = permissionStr.substring(0, dot);
            if (EXECUTABLE_NODES.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static void registerPresets() {
        for (PermissionPreset preset : PermissionPreset.values()) {
            if (Bukkit.getPluginManager().getPermission(preset.node()) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(preset.node(), PermissionDefault.FALSE));
            }
        }
    }

    /** Registers the node so admins can discover it, and records the parent link {@link #canReach} walks */
    static void registerPermission(@NonNull String permissionStr) {
        if (Bukkit.getPluginManager().getPermission(permissionStr) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(permissionStr, PermissionDefault.OP));
        }
        int lastDot = permissionStr.lastIndexOf('.');
        if (lastDot > 0) {
            String parentStr = permissionStr.substring(0, lastDot);
            CHILD_NODES.computeIfAbsent(parentStr, ignored -> ConcurrentHashMap.newKeySet()).add(permissionStr);
            registerPermission(parentStr);
        }
    }

    /** Whether the node is shown */
    public static boolean canReach(@NonNull CommandSender sender, @NonNull String permissionNode) {
        return canReach(sender, permissionNode, inheritedVerdict(sender, permissionNode));
    }

    private static boolean canReach(@NonNull CommandSender sender, @NonNull String node, @Nullable Boolean inherited) {
        if (EXECUTABLE_NODES.contains(node)) {
            return allows(sender, node, inherited);
        }
        Set<String> children = CHILD_NODES.get(node);
        if (children == null) {
            return false;
        }
        Boolean below = inherited;
        if (sender.isPermissionSet(node)) {
            below = sender.hasPermission(node);
        }
        for (String child : children) {
            if (canReach(sender, child, below)) {
                return true;
            }
        }
        return false;
    }

    /** The specificity ladder (command itself -> whatever closest parent said -> bundle naming it -> its own default) */
    private static boolean allows(@NonNull CommandSender sender, @NonNull String node, @Nullable Boolean inherited) {
        if (sender.isPermissionSet(node)) {
            return sender.hasPermission(node);
        }
        if (inherited != null) {
            return inherited;
        }
        return grantedByPreset(sender, node) || sender.hasPermission(node);
    }

    private static @Nullable Boolean inheritedVerdict(@NonNull CommandSender sender, @NonNull String permissionNode) {
        for (int dot = permissionNode.lastIndexOf('.'); dot > 0; dot = permissionNode.lastIndexOf('.', dot - 1)) {
            String ancestor = permissionNode.substring(0, dot);
            if (sender.isPermissionSet(ancestor)) {
                return sender.hasPermission(ancestor);
            }
        }
        return null;
    }

    private static boolean grantedByPreset(@NonNull CommandSender sender, @NonNull String permissionNode) {
        Set<PermissionPreset> presets = NODE_PRESETS.get(permissionNode);
        if (presets == null) {
            return false;
        }
        for (PermissionPreset preset : presets) {
            if (sender.hasPermission(preset.node())) {
                return true;
            }
        }
        return false;
    }
}
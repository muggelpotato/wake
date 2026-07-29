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
 * Registers the permission nodes. <br>
 * 1. An explicit {@code false} on a node or any parent denies <br>
 * 2. The node itself granted allows, whether directly or through a {@link PermissionPreset} the sender holds <br>
 * 3. A granted child also allows, so permission to a sub-command reveals the path leading to it <br>
 * 4. All nodes default to OP <br>
 * Never construct permission strings elsewhere.
 * This class only ever sees what {@link WakeCommandManager} derives from the command tree.
 */
public class PermissionManager {
    private static final Map<String, Set<String>> CHILD_NODES = new ConcurrentHashMap<>();
    private static final Map<String, Set<PermissionPreset>> NODE_PRESETS = new ConcurrentHashMap<>();

    static void assignPresets(@NonNull String permissionStr, @Nullable Set<PermissionPreset> presets) {
        if (presets == null || presets.isEmpty()) {
            return;
        }
        NODE_PRESETS.merge(permissionStr, Set.copyOf(presets), (held, added) -> {
            Set<PermissionPreset> union = EnumSet.copyOf(held);
            union.addAll(added);
            return Set.copyOf(union);
        });
    }

    static void registerPresets() {
        for (PermissionPreset preset : PermissionPreset.values()) {
            if (Bukkit.getPluginManager().getPermission(preset.node()) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(preset.node(), PermissionDefault.FALSE));
            }
        }
    }

    static @NonNull Permission registerPermission(@NonNull String permissionStr) {
        Permission perm = Bukkit.getPluginManager().getPermission(permissionStr);
        if (perm == null) {
            perm = new Permission(permissionStr, PermissionDefault.OP);
            Bukkit.getPluginManager().addPermission(perm);
        }
        int lastDot = permissionStr.lastIndexOf('.');
        if (lastDot > 0) {
            String parentStr = permissionStr.substring(0, lastDot);
            CHILD_NODES.computeIfAbsent(parentStr, ignored -> ConcurrentHashMap.newKeySet()).add(permissionStr);
            Permission parentPerm = registerPermission(parentStr);
            if (!parentPerm.getChildren().containsKey(permissionStr)) {
                parentPerm.getChildren().put(permissionStr, true);
                parentPerm.recalculatePermissibles();
            }
        }
        return perm;
    }

    public static boolean hasAccess(@NonNull CommandSender sender, @NonNull String permissionNode) {
        if (permissionNode.isEmpty()) {
            return true;
        }
        if (deniedOnPath(sender, permissionNode)) {
            return false;
        }
        return granted(sender, permissionNode) || anyChildGrants(sender, permissionNode);
    }

    private static boolean anyChildGrants(@NonNull CommandSender sender, @NonNull String node) {
        Set<String> children = CHILD_NODES.get(node);
        if (children == null) {
            return false;
        }
        for (String child : children) {
            if (sender.isPermissionSet(child) && !sender.hasPermission(child)) {
                continue;
            }
            if (granted(sender, child) || anyChildGrants(sender, child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean deniedOnPath(@NonNull CommandSender sender, @NonNull String permissionNode) {
        for (int dot = permissionNode.indexOf('.'); dot >= 0; dot = permissionNode.indexOf('.', dot + 1)) {
            String parent = permissionNode.substring(0, dot);
            if (sender.isPermissionSet(parent) && !sender.hasPermission(parent)) {
                return true;
            }
        }
        return sender.isPermissionSet(permissionNode) && !sender.hasPermission(permissionNode);
    }

    private static boolean granted(@NonNull CommandSender sender, @NonNull String permissionNode) {
        return sender.hasPermission(permissionNode) || grantedByPreset(sender, permissionNode);
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
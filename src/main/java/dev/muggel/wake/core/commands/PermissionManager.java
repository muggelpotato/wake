package dev.muggel.wake.core.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers the permission nodes. <br>
 * 1. An explicit {@code false} on a node or any parent denies <br>
 * 2. The node itself granted allows <br>
 * 3. A granted child also allows, so permission to a sub-command reveals the path leading to it <br>
 * 4. All nodes default to OP <br>
 * Never construct permission strings elsewhere.
 * This class only ever sees what {@link WakeCommandManager} derives from the command tree.
 */
public class PermissionManager {
    private static final Set<String> REGISTERED_PERMISSIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static @NonNull Permission registerPermission(String permissionStr) {
        REGISTERED_PERMISSIONS.add(permissionStr);
        Permission perm = Bukkit.getPluginManager().getPermission(permissionStr);
        if (perm == null) {
            perm = new Permission(permissionStr, PermissionDefault.OP);
            Bukkit.getPluginManager().addPermission(perm);
        }
        int lastDot = permissionStr.lastIndexOf('.');
        if (lastDot > 0) {
            String parentStr = permissionStr.substring(0, lastDot);
            Permission parentPerm = registerPermission(parentStr);
            if (!parentPerm.getChildren().containsKey(permissionStr)) {
                parentPerm.getChildren().put(permissionStr, true);
                parentPerm.recalculatePermissibles();
            }
        }
        return perm;
    }

    public static boolean hasAccess(CommandSender sender, String permissionNode) {
        if (sender == null || permissionNode == null || permissionNode.isEmpty()) {
            return true;
        }
        String[] parts = permissionNode.split("\\.");
        StringBuilder currentPath = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) currentPath.append(".");
            currentPath.append(parts[i]);
            String node = currentPath.toString();
            if (sender.isPermissionSet(node) && !sender.hasPermission(node)) {
                return false;
            }
        }
        if (sender.hasPermission(permissionNode)) {
            return true;
        }
        String prefix = permissionNode + ".";
        for (String registered : REGISTERED_PERMISSIONS) {
            if (registered.startsWith(prefix)) {
                if (sender.hasPermission(registered)) {
                    return true;
                }
            }
        }
        return false;
    }
}
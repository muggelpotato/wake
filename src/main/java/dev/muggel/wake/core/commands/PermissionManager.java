package dev.muggel.wake.core.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 1. any parent node set to false -> access denied
     * 2. node set to true -> access granted
     * 3. any child permission of this parent node true -> access granted
     */

    public static boolean hasAccess(CommandSender sender, String permissionNode) {
        if (sender == null || permissionNode == null || permissionNode.isEmpty()) {
            return true;
        }

        // 1
        String[] parts = permissionNode.split("\\.");
        StringBuilder currentPath = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) currentPath.append(".");
            currentPath.append(parts[i]);
            String node = currentPath.toString();
            if (sender.isPermissionSet(node) && !sender.hasPermission(node)) {
                return false;
            }
        }

        // 2
        if (sender.hasPermission(permissionNode)) {
            return true;
        }

        // 3 bottom-up check
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

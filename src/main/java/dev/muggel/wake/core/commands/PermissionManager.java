package dev.muggel.wake.core.commands;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.NonNull;

public class PermissionManager {
    public static @NonNull Permission registerPermission(String permissionStr) {
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
}

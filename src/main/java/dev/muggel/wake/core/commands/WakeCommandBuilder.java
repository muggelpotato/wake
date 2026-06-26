package dev.muggel.wake.core.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public class WakeCommandBuilder {
    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name, String permission) {
        PermissionManager.registerPermission(permission);
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> moduleLiteral(String name, String permission, Wake plugin, Class<? extends WakeModule> moduleClass) {
        PermissionManager.registerPermission(permission);
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission) && plugin.getModule(moduleClass) != null);
    }
}

package dev.muggel.wake.core.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

public class WakeCommandBuilder {
    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name, String permission) {
        PermissionManager.registerPermission(permission);
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(permission));
    }
}

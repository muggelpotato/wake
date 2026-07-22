package dev.muggel.wake.features.drydock.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.features.drydock.api.DrydockService;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

public final class DrydockCommandHelper {
    private DrydockCommandHelper() {}

    public static @Nullable DrydockService requireService(Wake plugin, CommandSender sender) {
        return CommandHelper.requireService(DrydockService.class, plugin, sender, "drydock");
    }
}
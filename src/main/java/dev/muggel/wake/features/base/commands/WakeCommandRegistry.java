package dev.muggel.wake.features.base.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

public class WakeCommandRegistry {
    private final Wake plugin;

    public WakeCommandRegistry(Wake plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            LiteralArgumentBuilder<CommandSourceStack> wakeRoot = WakeCommandBuilder.literal("wake", "wake.command.wake");
            
            WakeReloadCommand.register(wakeRoot, plugin);
            WakeKillBoatCommand.register(wakeRoot, plugin);
            WakeKillEmptyBoatsCommand.register(wakeRoot, plugin);

            commands.register(wakeRoot.build(), "Main command for the Wake plugin", List.of("wa"));
        });
    }
}

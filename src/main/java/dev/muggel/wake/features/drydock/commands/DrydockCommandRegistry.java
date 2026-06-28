package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.drydock.DrydockModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

public class DrydockCommandRegistry {
    private final Wake plugin;

    public DrydockCommandRegistry(Wake plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            LiteralArgumentBuilder<CommandSourceStack> root = WakeCommandBuilder.moduleLiteral("drydock", "wake.drydock.commands", plugin, DrydockModule.class);
            
            DrydockGetBoatCommand.register(root, plugin);
            DrydockBoostpadCommand.register(root, plugin);
            
            commands.register(root.build(), "Drydock utility commands", List.of("dd"));
        });
    }
}

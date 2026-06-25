package dev.muggel.wake.features.drydock.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.drydock.api.DrydockService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

public class DrydockCommandRegistry {
    private final Wake plugin;
    private final DrydockService service;

    public DrydockCommandRegistry(Wake plugin, DrydockService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            LiteralArgumentBuilder<CommandSourceStack> root = WakeCommandBuilder.literal("drydock", "wake.drydock.commands");
            
            DrydockGetBoatCommand.register(root, plugin, service);
            
            commands.register(root.build(), "Drydock utility commands", List.of("dd"));
        });
    }
}

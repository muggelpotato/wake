package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

public class OBUCommandRegistry {
    private final Wake plugin;

    public OBUCommandRegistry(Wake plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            LiteralArgumentBuilder<CommandSourceStack> wobuRoot = WakeCommandBuilder.literal("wakeobu", "wake.obu.commands");

            OBUHelpCommand.register(wobuRoot, plugin);
            OBUDefaultsCommand.register(wobuRoot, plugin);
            OBUContextCommand.register(wobuRoot, plugin);
            OBUStatusCommand.register(wobuRoot, plugin);
            OBUSandboxCommand.register(wobuRoot, plugin);
            OBUSettingsCommand.register(wobuRoot, plugin);
            OBUClearCommand.register(wobuRoot, plugin);

            commands.register(wobuRoot.build(), "OpenBoatUtils settings and configuration.", List.of("wobu"));
        });
    }
}

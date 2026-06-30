package dev.muggel.wake.features.base;

import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.WakeCommandManager;
import dev.muggel.wake.core.module.AbstractModule;
import dev.muggel.wake.features.base.commands.WakeKillBoatCommand;
import dev.muggel.wake.features.base.commands.WakeKillEmptyBoatsCommand;
import dev.muggel.wake.features.base.commands.WakeReloadCommand;
import dev.muggel.wake.features.base.listeners.BoatListener;

public class BaseModule extends AbstractModule {
    public BaseModule() {
        super("base");
    }

    @Override
    protected void onModuleEnable() {
        registerListener(new BoatListener(this));

        CommandNode wakeRoot = CommandNode.literal("wake")
                .withModule(BaseModule.class)
                .withDescription("Main command for Wake")
                .aliases("wa")
                .addSubcommand(WakeReloadCommand.getNode(plugin))
                .addSubcommand(WakeKillBoatCommand.getNode(plugin))
                .addSubcommand(WakeKillEmptyBoatsCommand.getNode(plugin));
        WakeCommandManager.register(wakeRoot);
    }

    public boolean isKillBoatOnExit() {
        return plugin.getStateManager().get("killboatonexit", false);
    }

    @Override
    protected void onModuleDisable() {
        WakeCommandManager.unregister("wake");
    }

    @Override
    public void reload() {
        plugin.getStateManager().load();
    }
}

package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.features.drydock.boostpads.BoostpadConfig;
import dev.muggel.wake.features.drydock.boostpads.BoostpadDetectorListener;
import dev.muggel.wake.features.drydock.boostpads.BoostpadRegistry;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BoostpadListCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("list")
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        Component blocks = blocks(plugin.getMessageManager(), DrydockCommandHelper.boostpads(plugin));
        plugin.getMessageManager().send(sender, "commands.drydock.boostpad.status.layout",
                Placeholder.component("state", state(plugin)),
                Placeholder.component("cooldown", cooldown(plugin)),
                Placeholder.component("early_out", earlyOut(plugin)),
                Placeholder.component("blocks", blocks),
                CommandHelper.hint(plugin, "commands.drydock.boostpad.status.hint"));
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull Component state(@NonNull Wake plugin) {
        return plugin.getMessageManager().getComponent(
                plugin.getStateDao().get(BoostpadDetectorListener.STATE_KEY_ENABLED, BoostpadDetectorListener.DEFAULT_ENABLED)
                        ? "commands.drydock.boostpad.status.state.enabled"
                        : "commands.drydock.boostpad.status.state.disabled");
    }

    private static @NonNull Component cooldown(@NonNull Wake plugin) {
        long cooldownMs = BoostpadDetectorListener.globalCooldownMs(plugin);
        return cooldownMs > 0
                ? plugin.getMessageManager().getComponent("commands.drydock.boostpad.status.cooldown.set", Placeholder.unparsed("delay", String.valueOf(cooldownMs)))
                : plugin.getMessageManager().getComponent("commands.drydock.boostpad.status.cooldown.unset");
    }

    private static @NonNull Component earlyOut(@NonNull Wake plugin) {
        return plugin.getMessageManager().getComponent("commands.drydock.boostpad.status.early_out.text",
                Placeholder.component("x", axis(plugin, "x", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_X, BoostpadDetectorListener.DEFAULT_EARLY_OUT_X)),
                Placeholder.component("y", axis(plugin, "y", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Y, BoostpadDetectorListener.DEFAULT_EARLY_OUT_Y)),
                Placeholder.component("z", axis(plugin, "z", BoostpadDetectorListener.STATE_KEY_EARLY_OUT_Z, BoostpadDetectorListener.DEFAULT_EARLY_OUT_Z)));
    }

    private static @NonNull Component axis(@NonNull Wake plugin, @NonNull String axis, @NonNull String stateKey, boolean fallback) {
        String key = plugin.getStateDao().get(stateKey, fallback)
                ? "commands.drydock.boostpad.status.early_out.axis_enabled"
                : "commands.drydock.boostpad.status.early_out.axis_disabled";
        return plugin.getMessageManager().getComponent(key, Placeholder.unparsed("axis", axis));
    }

    private static @NonNull Component blocks(@NonNull MessageManager messages, @NonNull BoostpadRegistry boostpads) {
        if (!boostpads.isLoaded()) {
            return messages.getComponent("commands.drydock.boostpad.status.blocks.unavailable");
        }
        List<BoostpadConfig> configs = new ArrayList<>(boostpads.cachedBoostpads().values());
        if (configs.isEmpty()) {
            return messages.getComponent("commands.drydock.boostpad.status.blocks.empty");
        }
        configs.sort(Comparator.comparing((BoostpadConfig config) -> CommandHelper.stripNamespace(config.blockKey()))
                .thenComparing(BoostpadConfig::blockKey));
        List<Component> lines = new ArrayList<>(configs.size());
        for (BoostpadConfig config : configs) {
            String key = config.enabled() ? "commands.drydock.boostpad.status.blocks.item" : "commands.drydock.boostpad.status.blocks.item_disabled";
            lines.add(messages.getComponent(key,
                    Placeholder.unparsed("block", CommandHelper.stripNamespace(config.blockKey())),
                    Placeholder.unparsed("x", decimal(config.forceX())),
                    Placeholder.unparsed("y", decimal(config.forceY())),
                    Placeholder.unparsed("z", decimal(config.forceZ())),
                    Placeholder.unparsed("delay", String.valueOf(config.delayMs())),
                    Placeholder.unparsed("padding", decimal(config.padding()))));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static @NonNull String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
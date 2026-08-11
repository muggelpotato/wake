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
        boolean globalEnabled = plugin.getStateDao().get(BoostpadDetectorListener.STATE_KEY_ENABLED, BoostpadDetectorListener.DEFAULT_ENABLED);
        String statusKey = globalEnabled ? "commands.drydock.boostpad.status_enabled" : "commands.drydock.boostpad.status_disabled";
        Component blocks = blocks(plugin.getMessageManager(), DrydockCommandHelper.boostpads(plugin));
        plugin.getMessageManager().send(sender, statusKey, Placeholder.component("blocks", blocks), Placeholder.component("cooldown", cooldown(plugin)), CommandHelper.hint(plugin, "commands.drydock.boostpad.status_hint"));
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull Component cooldown(@NonNull Wake plugin) {
        long cooldownMs = BoostpadDetectorListener.globalCooldownMs(plugin);
        return cooldownMs > 0
                ? plugin.getMessageManager().getComponent("commands.drydock.boostpad.global_cooldown", Placeholder.unparsed("delay", String.valueOf(cooldownMs)))
                : plugin.getMessageManager().getComponent("commands.drydock.boostpad.global_cooldown_off");
    }

    private static @NonNull Component blocks(@NonNull MessageManager messages, @NonNull BoostpadRegistry boostpads) {
        if (!boostpads.isLoaded()) {
            return messages.getComponent("commands.drydock.boostpad.unavailable");
        }
        List<BoostpadConfig> configs = new ArrayList<>(boostpads.cachedBoostpads().values());
        if (configs.isEmpty()) {
            return messages.getComponent("commands.drydock.boostpad.empty");
        }
        configs.sort(Comparator.comparing((BoostpadConfig config) -> CommandHelper.stripNamespace(config.blockKey()))
                .thenComparing(BoostpadConfig::blockKey));
        List<Component> lines = new ArrayList<>(configs.size());
        for (BoostpadConfig config : configs) {
            String key = config.enabled() ? "commands.drydock.boostpad.item_enabled" : "commands.drydock.boostpad.item_disabled";
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
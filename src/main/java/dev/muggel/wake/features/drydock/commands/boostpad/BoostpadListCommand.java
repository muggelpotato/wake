package dev.muggel.wake.features.drydock.commands.boostpad;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.text.MessageManager;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.PermissionPreset;
import dev.muggel.wake.features.drydock.api.BoostpadConfig;
import dev.muggel.wake.features.drydock.api.DrydockService;
import dev.muggel.wake.features.drydock.commands.DrydockCommandHelper;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.Map;

public class BoostpadListCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("list")
                .withPreset(PermissionPreset.PLAYER)
                .executesSender((ctx, sender) -> execute(ctx, plugin));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        DrydockService service = DrydockCommandHelper.requireService(plugin, sender);
        if (service == null) return 0;
        boolean globalEnabled = plugin.getStateDao().get(BoostpadCommand.STATE_KEY_ENABLED, true);
        Map<String, BoostpadConfig> configs = service.cachedBoostpads();
        Component blocksComp = Component.empty();
        if (configs.isEmpty()) {
            blocksComp = plugin.getMessageManager().getComponent("commands.drydock.boostpad.empty");
        } else {
            boolean first = true;
            for (BoostpadConfig config : configs.values()) {
                if (!first) {
                    blocksComp = blocksComp.append(Component.newline());
                }
                first = false;
                String key = config.enabled() ? "commands.drydock.boostpad.item_enabled" : "commands.drydock.boostpad.item_disabled";
                Component itemComp = plugin.getMessageManager().getComponent(key,
                        Placeholder.unparsed("block", MessageManager.stripNamespace(config.blockKey())),
                        Placeholder.unparsed("x", String.format(Locale.ROOT, "%.2f", config.forceX())),
                        Placeholder.unparsed("y", String.format(Locale.ROOT, "%.2f", config.forceY())),
                        Placeholder.unparsed("z", String.format(Locale.ROOT, "%.2f", config.forceZ())),
                        Placeholder.unparsed("delay", String.valueOf(config.delayMs())),
                        Placeholder.unparsed("hitbox", String.valueOf(config.hitboxPercent()))
                );
                blocksComp = blocksComp.append(itemComp);
            }
        }
        String statusKey = globalEnabled ? "commands.drydock.boostpad.status_enabled" : "commands.drydock.boostpad.status_disabled";
        plugin.getMessageManager().send(sender, statusKey, Placeholder.component("blocks", blocksComp));
        return Command.SINGLE_SUCCESS;
    }
}
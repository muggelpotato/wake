package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxSwitchCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("switch")
                .arguments(CommandNode.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesPlayer((ctx, player) -> execute(ctx, player, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Player player, Wake plugin) {
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String key = OBUContextManager.sandboxKey(name, player.getUniqueId());
        OBUContext context = contextManager.getContext(key);
        if (context == null || !context.isSandbox()) {
            plugin.getMessageManager().send(player, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", name));
            return 0;
        }
        SandboxCommandHelper.enterSandbox(player, key, service);
        plugin.getMessageManager().send(player, "commands.obu.sandbox.switched", Placeholder.unparsed("sandbox", name));
        SandboxCommandHelper.sendHintIfEnabled(plugin, player);
        return Command.SINGLE_SUCCESS;
    }
}
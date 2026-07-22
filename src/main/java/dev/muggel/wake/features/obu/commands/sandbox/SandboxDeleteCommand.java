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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

public class SandboxDeleteCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("delete")
                .arguments(CommandNode.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String key = SandboxCommandHelper.sandboxKeyFor(sender, name);
        OBUContext context = contextManager.getContext(key);
        if (context == null || !context.isSandbox()) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", name));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.deleted", Placeholder.unparsed("sandbox", name));
        for (Player online : service.deleteContextAndEvict(key)) {
            plugin.getMessageManager().send(online, "commands.obu.sandbox.kicked", Placeholder.unparsed("sandbox", name));
        }
        return Command.SINGLE_SUCCESS;
    }
}
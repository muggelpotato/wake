package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Locale;

public class SandboxCreateCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("create")
                .arguments(CommandNode.argument("name", StringArgumentType.string())
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        if (!SandboxCommandHelper.isValidSandboxName(name)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.invalid_name", Placeholder.unparsed("sandbox", name));
            return 0;
        }
        String key = SandboxCommandHelper.sandboxKeyFor(sender, name);
        if (!service.createSandbox(key, (sender instanceof Player p) ? p.getUniqueId() : null)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", name));
            return 0;
        }
        if (sender instanceof Player player) {
            SandboxCommandHelper.enterSandbox(player, key, service);
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.created", Placeholder.unparsed("sandbox", name));
        SandboxCommandHelper.sendHintIfEnabled(plugin, sender);
        return Command.SINGLE_SUCCESS;
    }
}
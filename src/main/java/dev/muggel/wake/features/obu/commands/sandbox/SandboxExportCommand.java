package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.NonNull;

import java.util.StringJoiner;
import java.util.logging.Level;

public class SandboxExportCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("export")
                .arguments(CommandNode.argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> SandboxCommandHelper.suggestOwnSandboxes(ctx, builder, plugin))
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String key = SandboxCommandHelper.sandboxKeyFor(sender, name);
        OBUContext context = contextManager.getContext(key);
        if (context == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", name));
            return 0;
        }
        StringJoiner joiner = new StringJoiner(";");
        for (OBUSetting setting : context.settings()) {
            joiner.add(setting.definition().id() + ":" + String.join(" ", setting.args()));
        }
        String shareCode;
        try {
            shareCode = SandboxCommandHelper.encodeShareCode(joiner.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to process sandbox share code", e);
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.export_fail", Placeholder.unparsed("error", reason));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.header_export", Placeholder.unparsed("sandbox", OBUContextManager.displayName(context.name())));
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.code", Placeholder.parsed("code", shareCode));
        return Command.SINGLE_SUCCESS;
    }
}
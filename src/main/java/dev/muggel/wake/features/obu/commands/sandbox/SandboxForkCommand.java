package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class SandboxForkCommand {
    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("fork")
                .arguments(
                        CommandNode.argument("contextToLoad", StringArgumentType.string())
                                .suggests((ctx, builder) -> suggestForkSources(ctx, builder, plugin)),
                        CommandNode.argument("newName", StringArgumentType.string())
                                .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String contextToLoad = StringArgumentType.getString(ctx, "contextToLoad").toLowerCase(Locale.ROOT);
        String newName = StringArgumentType.getString(ctx, "newName").toLowerCase(Locale.ROOT);
        if (!SandboxCommandHelper.isValidSandboxName(newName)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.invalid_name", Placeholder.unparsed("sandbox", newName));
            return 0;
        }
        OBUContext sourceContext = sender instanceof Player p
                ? contextManager.getContext(OBUContextManager.sandboxKey(contextToLoad, p.getUniqueId()))
                : null;
        if (sourceContext == null) {
            sourceContext = contextManager.getContext(contextToLoad);
            if (sourceContext != null && sourceContext.isSandbox() && sender instanceof Player) {
                sourceContext = null;
            }
        }
        if (sourceContext == null) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", contextToLoad));
            return 0;
        }
        String newKey = SandboxCommandHelper.sandboxKeyFor(sender, newName);
        if (!service.createSandbox(newKey, (sender instanceof Player p) ? p.getUniqueId() : null)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", newName));
            return 0;
        }
        contextManager.addSettings(newKey, sourceContext.settings());
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.forked", Placeholder.unparsed("source", OBUContextManager.displayName(sourceContext.name())), Placeholder.unparsed("sandbox", newName));
        if (sender instanceof Player p) {
            SandboxCommandHelper.enterSandbox(p, newKey, service);
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.unparsed("sandbox", newName));
            SandboxCommandHelper.sendHintIfEnabled(plugin, sender);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static @NonNull CompletableFuture<Suggestions> suggestForkSources(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin, c -> true);
    }
}
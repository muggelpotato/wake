package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class ClearCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-clear")
                .withHelpKey("commands.obu.help.clear")
                .arguments(CommandNode.argument("setting", NameArgumentType.greedy())
                        .suggests((ctx, builder) -> suggestSetting(ctx, builder, plugin))
                        .executesEntityOrAimedBoat((ctx, target) -> execute(ctx, target, plugin)));
    }

    private static CompletableFuture<Suggestions> suggestSetting(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        if (!(CommandHelper.actingSender(ctx.getSource()) instanceof Player player)) {
            return builder.buildFuture();
        }
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandboxName = service.getPlayerActiveSandbox(player);
        Map<String, OBUSetting> active = new HashMap<>();
        if (sandboxName != null) {
            OBUContext base = contextManager.getContext(sandboxName);
            if (base != null) {
                for (OBUSetting s : base.settings()) active.put(s.getUniqueKey(), s);
            }
        }
        active.putAll(service.getSyncManager().getLocalOverrides(player.getUniqueId()));
        return CommandHelper.suggestMatching(builder,
                active.values().stream().map(s -> s.definition().name()).distinct().toList());
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Entity target, Wake plugin) {
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String settingKey = StringArgumentType.getString(ctx, "setting");
        CommandSender sender = ctx.getSource().getSender();
        OBUDefinition def = OBUDefinition.get(settingKey);
        Predicate<OBUSetting> matches = def != null
                ? s -> s.definition() == def
                : s -> s.getUniqueKey().equals(settingKey);
        var overrides = service.getSyncManager().getLocalOverrides(target.getUniqueId());
        boolean cleared = false;
        String defNameForMessage = def != null ? def.name() : settingKey;
        if (target instanceof Player player) {
            String sandboxName = service.getPlayerActiveSandbox(player);
            List<OBUSetting> matchedOverrides = overrides.values().stream().filter(matches).toList();
            if (!matchedOverrides.isEmpty()) {
                defNameForMessage = matchedOverrides.getFirst().definition().name();
                for (OBUSetting s : matchedOverrides) {
                    service.getSyncManager().removeLocalOverride(player.getUniqueId(), s.getUniqueKey());
                }
                plugin.getMessageManager().send(sender, "commands.obu.clear.temp", Placeholder.unparsed("setting", defNameForMessage), Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, player, sender)));
                cleared = true;
            }
            if (sandboxName != null) {
                OBUContext sandbox = contextManager.getContext(sandboxName);
                if (sandbox != null) {
                    boolean sandboxCleared = false;
                    for (OBUSetting s : sandbox.settings()) {
                        if (matches.test(s) && contextManager.removeContextSetting(sandboxName, s.getUniqueKey())) {
                            defNameForMessage = s.definition().name();
                            sandboxCleared = true;
                        }
                    }
                    if (sandboxCleared) {
                        plugin.getMessageManager().send(sender, "commands.obu.clear.sandbox", Placeholder.unparsed("setting", defNameForMessage), Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandboxName)));
                        cleared = true;
                    }
                }
            } else if (!cleared) {
                String baseName = service.getActiveContextName(player);
                boolean isBase = false;
                OBUContext base = contextManager.getContext(baseName);
                if (base != null) {
                    isBase = base.settings().stream().anyMatch(matches);
                }
                if (!isBase && OBUContextManager.inheritsDefault(baseName)) {
                    OBUContext defaults = contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT);
                    if (defaults != null) {
                        isBase = defaults.settings().stream().anyMatch(matches);
                    }
                }
                if (isBase) {
                    plugin.getMessageManager().send(sender, "commands.obu.clear.base_blocked", Placeholder.unparsed("setting", defNameForMessage));
                    return 0;
                }
            }
            if (cleared) {
                service.getSyncManager().syncPlayer(player);
            }
        } else if (target instanceof Boat boat) {
            List<OBUSetting> matchedOverrides = overrides.values().stream().filter(matches).toList();
            if (!matchedOverrides.isEmpty()) {
                defNameForMessage = matchedOverrides.getFirst().definition().name();
                for (OBUSetting s : matchedOverrides) {
                    service.getSyncManager().removeLocalOverride(boat.getUniqueId(), s.getUniqueKey());
                }
                plugin.getMessageManager().send(sender, "commands.obu.clear.temp", Placeholder.unparsed("setting", defNameForMessage), Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, boat, sender)));
                cleared = true;
            }
            if (cleared) {
                service.getSyncManager().broadcastSync(boat);
            }
        }
        if (!cleared) {
            if (def == null) {
                plugin.getMessageManager().send(sender, "commands.obu.clear.unknown", Placeholder.unparsed("setting", settingKey));
                return 0;
            }
            plugin.getMessageManager().send(sender, "commands.obu.clear.missing", Placeholder.unparsed("setting", defNameForMessage), Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }
}
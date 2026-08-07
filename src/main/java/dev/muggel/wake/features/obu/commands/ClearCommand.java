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
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
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
        OBUSyncManager sync = OBUCommandHelper.sync(plugin);
        Map<String, OBUSetting> clearable = new HashMap<>();
        if (player.getTargetEntity(CommandNode.TargetType.AIM_DISTANCE) instanceof Boat boat) {
            clearable.putAll(sync.getLocalOverrides(boat.getUniqueId()));
        } else {
            String sandboxName = OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId());
            OBUContext sandbox = sandboxName == null ? null : OBUCommandHelper.contexts(plugin).getContext(sandboxName);
            if (sandbox != null) {
                for (OBUSetting s : sandbox.settings()) clearable.put(s.uniqueKey(), s);
            }
            clearable.putAll(sync.getLocalOverrides(player.getUniqueId()));
        }
        return CommandHelper.suggestMatching(builder,
                clearable.values().stream().map(s -> s.definition().name()).distinct().toList());
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Entity target, Wake plugin) {
        ActiveContexts active = OBUCommandHelper.active(plugin);
        OBUSyncManager sync = OBUCommandHelper.sync(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String settingKey = StringArgumentType.getString(ctx, "setting");
        CommandSender sender = ctx.getSource().getSender();
        OBUDefinition def = OBUDefinition.byName(settingKey);
        Predicate<OBUSetting> matches = def != null
                ? s -> s.definition() == def
                : s -> s.uniqueKey().equals(settingKey);
        Map<String, OBUSetting> overrides = sync.getLocalOverrides(target.getUniqueId());
        boolean cleared = false;
        String settingName = def != null ? def.name() : settingKey;
        List<OBUSetting> matchedOverrides = overrides.values().stream().filter(matches).toList();
        if (!matchedOverrides.isEmpty()) {
            settingName = matchedOverrides.getFirst().definition().name();
            for (OBUSetting s : matchedOverrides) {
                sync.removeLocalOverride(target.getUniqueId(), s.uniqueKey());
            }
            plugin.getMessageManager().send(sender, "commands.obu.clear.temp", Placeholder.unparsed("setting", settingName), Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
            cleared = true;
        }
        if (target instanceof Player player) {
            String sandboxName = active.sandboxOf(player.getUniqueId());
            if (sandboxName != null) {
                OBUContext sandbox = contextManager.getContext(sandboxName);
                if (sandbox != null) {
                    boolean sandboxCleared = false;
                    for (OBUSetting s : sandbox.settings()) {
                        if (matches.test(s) && contextManager.removeContextSetting(sandboxName, s.uniqueKey())) {
                            settingName = s.definition().name();
                            sandboxCleared = true;
                        }
                    }
                    if (sandboxCleared) {
                        plugin.getMessageManager().send(sender, "commands.obu.clear.sandbox", Placeholder.unparsed("setting", settingName), Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandboxName)));
                        cleared = true;
                    }
                }
            } else if (!cleared && inBaseContext(contextManager, active.contextOf(player.getUniqueId()), matches)) {
                plugin.getMessageManager().send(sender, "commands.obu.clear.base_blocked", Placeholder.unparsed("setting", settingName));
                return 0;
            }
            if (cleared) {
                sync.syncPlayer(player);
            }
        } else if (cleared && target instanceof Boat boat) {
            sync.broadcastSync(boat);
        }
        if (!cleared) {
            if (def == null) {
                plugin.getMessageManager().send(sender, "commands.obu.clear.unknown", Placeholder.unparsed("setting", settingKey));
                return 0;
            }
            plugin.getMessageManager().send(sender, "commands.obu.clear.missing", Placeholder.unparsed("setting", settingName), Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean inBaseContext(@NonNull OBUContextManager contextManager, @NonNull String baseName, @NonNull Predicate<OBUSetting> matches) {
        OBUContext base = contextManager.getContext(baseName);
        if (base != null && base.settings().stream().anyMatch(matches)) {
            return true;
        }
        OBUContext defaults = OBUContextManager.inheritsDefault(baseName)
                ? contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT)
                : null;
        return defaults != null && defaults.settings().stream().anyMatch(matches);
    }
}
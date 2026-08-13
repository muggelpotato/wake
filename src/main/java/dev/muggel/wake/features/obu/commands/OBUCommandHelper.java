package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.Scheduling;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.KeyListArgumentType;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import dev.muggel.wake.features.obu.protocol.SettingType;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.OBUVersions;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.clients.ClientRegistry;
import dev.muggel.wake.features.obu.delivery.ActiveContexts;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
import dev.muggel.wake.features.obu.clients.ClientRegistry.ClientState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class OBUCommandHelper {
    private OBUCommandHelper() {}

    public static @NonNull OBUModule module(@NonNull Wake plugin) {
        return loaded(plugin.getModule(OBUModule.class));
    }

    public static @NonNull ContextDelivery delivery(@NonNull Wake plugin) {
        return loaded(module(plugin).getDelivery());
    }

    public static @NonNull OBUContextManager contexts(@NonNull Wake plugin) {
        return loaded(module(plugin).getContextManager());
    }

    public static @NonNull OBUSyncManager sync(@NonNull Wake plugin) {
        return loaded(module(plugin).getSyncManager());
    }

    public static @NonNull ActiveContexts active(@NonNull Wake plugin) {
        return loaded(module(plugin).getActiveContexts());
    }

    private static @NonNull ClientRegistry clients(@NonNull Wake plugin) {
        return loaded(module(plugin).getClients());
    }

    private static <T> @NonNull T loaded(@Nullable T part) {
        if (part == null) {
            throw new IllegalStateException("OBU module is not loaded");
        }
        return part;
    }

    public static boolean requireClient(@NonNull Wake plugin, @NonNull CommandSourceStack source, @NonNull Object target) {
        if (!(target instanceof Player player)) {
            return true;
        }
        ClientState state = clients(plugin).state(player.getUniqueId());
        if (state == ClientState.DRIVEN) {
            return true;
        }
        String key = switch (state) {
            case UNSUPPORTED -> "commands.obu.unsupported_client";
            case UNKNOWN -> "commands.obu.reconnect_client";
            case null, default -> "commands.obu.requires_client";
        };
        CommandSender sender = source.getSender();
        plugin.getMessageManager().send(sender, key, Placeholder.component("target", targetPossessive(plugin, player, sender)));
        return false;
    }

    public static @Nullable OBUContext resolveForSubject(@NonNull Wake plugin, @NonNull CommandSender subject, @NonNull String name) {
        OBUContextManager contextManager = contexts(plugin);
        OBUContext context = contextManager.getContext(name);
        if (!(subject instanceof Player owner)) {
            return context;
        }
        return context != null && !context.isSandbox()
                ? context
                : contextManager.getContext(OBUContextManager.sandboxKey(name, owner.getUniqueId()));
    }

    private static @NonNull Collection<OBUSetting> editableSettings(@NonNull Wake plugin, @NonNull Player player) {
        OBUSyncManager sync = sync(plugin);
        if (player.getTargetEntity(CommandNode.TargetType.AIM_DISTANCE) instanceof Boat boat) {
            return sync.getLocalOverrides(boat.getUniqueId()).values();
        }
        Map<String, OBUSetting> editable = new LinkedHashMap<>();
        String sandboxName = active(plugin).sandboxOf(player.getUniqueId());
        OBUContext sandbox = sandboxName == null ? null : contexts(plugin).getContext(sandboxName);
        if (sandbox != null) {
            for (OBUSetting s : sandbox.settings()) editable.put(s.uniqueKey(), s);
        }
        editable.putAll(sync.getLocalOverrides(player.getUniqueId()));
        return editable.values();
    }

    public static String @NonNull [] argNames(@NonNull OBUDefinition def) {
        List<SettingType> types = def.types();
        List<String> custom = def.argNames();
        String[] names = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            if (!custom.isEmpty()) {
                names[i] = custom.get(i);
                continue;
            }
            String type = types.get(i).name().toLowerCase(Locale.ROOT);
            int count = 1;
            for (int j = 0; j < i; j++) if (types.get(j) == types.get(i)) count++;
            names[i] = count > 1 ? type + count : type;
        }
        return names;
    }

    public static @NonNull List<String> parsedArgs(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull List<String> names) {
        List<String> args = new ArrayList<>(names.size());
        for (String name : names) {
            args.add(String.valueOf(ctx.getArgument(name, Object.class)));
        }
        return args;
    }

    public static @NonNull CompletableFuture<Suggestions> suggestNarrowing(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, @NonNull Wake plugin, @NonNull OBUDefinition target, @NonNull List<String> boundArgs) {
        if (!(CommandHelper.actingSender(ctx.getSource()) instanceof Player player)) {
            return builder.buildFuture();
        }
        List<String> given = parsedArgs(ctx, boundArgs);
        SuggestionsBuilder word = KeyListArgumentType.atCurrentWord(builder);
        String before = KeyListArgumentType.entriesBefore(word);
        CompletableFuture<List<String>> held = new CompletableFuture<>();
        Scheduling.onMain(plugin, () -> held.complete(SettingSelector.suggestions(target, given, editableSettings(plugin, player))));
        return held.thenCompose(values -> CommandHelper.suggestMatching(word, before, values));
    }

    public static @NonNull Component tookEntries(@NonNull Wake plugin, @NonNull List<String> removed) {
        return removed.isEmpty()
                ? Component.empty()
                : plugin.getMessageManager().getComponent("commands.obu.clear.took", Placeholder.component("value", entryList(plugin, removed)));
    }

    public static boolean inBaseContext(@NonNull Wake plugin, @NonNull Player player, @NonNull Predicate<OBUSetting> matches) {
        OBUContextManager contextManager = contexts(plugin);
        String baseName = active(plugin).contextOf(player.getUniqueId());
        OBUContext base = contextManager.getContext(baseName);
        if (base != null && base.settings().stream().anyMatch(matches)) {
            return true;
        }
        OBUContext defaults = OBUContextManager.inheritsDefault(baseName)
                ? contextManager.getContext(OBUContextManager.DEFAULT_CONTEXT)
                : null;
        return defaults != null && defaults.settings().stream().anyMatch(matches);
    }

    public static @NonNull Component settingLine(@NonNull Wake plugin, @NonNull CommandSender subject, @NonNull OBUSetting setting, boolean inHover) {
        return settingLine(plugin, subject, setting, inHover, false, Set.of());
    }

    public static @NonNull Component settingLine(@NonNull Wake plugin, @NonNull CommandSender subject, @NonNull OBUSetting setting, boolean inHover, boolean shadowed, @NonNull Set<String> struck) {
        String key = inHover
                ? (shadowed ? "commands.obu.status.collapsed_line_overridden" : "commands.obu.status.collapsed_line")
                : (shadowed ? "commands.obu.status.overridden" : "commands.obu.status.line");
        Component line = plugin.getMessageManager().getComponent(key, Placeholder.unparsed("name", setting.definition().name()), Placeholder.component("value", displayValue(plugin, setting, inHover, struck)));
        return line.append(outdatedBadge(plugin, subject, setting));
    }

    public static @NonNull Component outdatedBadge(@NonNull Wake plugin, @NonNull CommandSender subject, @NonNull OBUSetting setting) {
        return pastClient(plugin, subject, setting)
                ? plugin.getMessageManager().getComponent("commands.obu.status.outdated_suffix")
                : Component.empty();
    }

    public static void warnIfPastClient(@NonNull Wake plugin, @NonNull CommandSender sender, @NonNull Entity target, @NonNull OBUSetting setting) {
        if (!pastClient(plugin, target, setting)) {
            return;
        }
        plugin.getMessageManager().send(sender, "commands.obu.settings.outdated_client",
                Placeholder.unparsed("setting", setting.definition().name()),
                Placeholder.component("target", targetPossessive(plugin, target, sender)));
    }

    private static boolean pastClient(@NonNull Wake plugin, @NonNull Object subject, @NonNull OBUSetting setting) {
        if (!(subject instanceof Player player)) {
            return false;
        }
        ClientRegistry registry = clients(plugin);
        UUID uuid = player.getUniqueId();
        return registry.isDriven(uuid) && OBUVersions.isPastCeiling(setting, registry.versionOf(uuid));
    }

    public static @NonNull Component displayValue(@NonNull Wake plugin, @NonNull OBUSetting setting, boolean inHover, @NonNull Set<String> struck) {
        List<SettingType> types = setting.definition().types();
        List<String> args = setting.args();
        TextComponent.Builder value = Component.text();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                value.append(Component.text(", "));
            }
            String arg = args.get(i);
            List<String> entries = i < types.size() && types.get(i).isList() ? List.of(arg.split(",")) : List.of();
            value.append(entries.size() < 2 ? loneEntry(plugin, arg, entries.size() == 1 && struck.contains(arg))
                    : inHover ? countChip(plugin, entries.size(), struck.containsAll(entries)) : collapsed(plugin, entries, struck));
        }
        return value.build();
    }

    public static @NonNull Component entryList(@NonNull Wake plugin, @NonNull List<String> entries) {
        return entries.size() < 2 ? Component.text(String.join(",", entries)) : collapsed(plugin, entries, Set.of());
    }

    private static @NonNull Component loneEntry(@NonNull Wake plugin, @NonNull String arg, boolean struck) {
        return struck
                ? plugin.getMessageManager().getComponent("commands.obu.status.entry_overridden", Placeholder.unparsed("entry", arg))
                : Component.text(arg);
    }

    private static @NonNull Component collapsed(@NonNull Wake plugin, @NonNull List<String> entries, @NonNull Set<String> struck) {
        Component hover = plugin.getMessageManager().getComponent("commands.obu.status.collapsed_header", Placeholder.unparsed("count", String.valueOf(entries.size())));
        for (String entry : entries) {
            hover = hover.append(Component.newline()).append(plugin.getMessageManager().getComponent(struck.contains(entry) ? "commands.obu.status.collapsed_entry_overridden" : "commands.obu.status.collapsed_entry",
                    Placeholder.unparsed("entry", entry)));
        }
        return countChip(plugin, entries.size(), struck.containsAll(entries)).hoverEvent(HoverEvent.showText(hover));
    }

    public static @NonNull Component countChip(@NonNull Wake plugin, int count, boolean struck) {
        return plugin.getMessageManager().getComponent(struck ? "commands.obu.status.collapsed_count_overridden" : "commands.obu.status.collapsed_count",
                Placeholder.unparsed("count", String.valueOf(count)));
    }

    public static @NonNull CompletableFuture<Suggestions> suggestContexts(
            @NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder,
            @NonNull Wake plugin, @NonNull Predicate<OBUContext> filter) {
        OBUContextManager contextManager = contexts(plugin);
        CommandSender sender = CommandHelper.actingSender(ctx.getSource());
        List<String> shown = new ArrayList<>();
        for (OBUContext context : contextManager.getContexts()) {
            if (OBUContextManager.isInternal(context.name()) || !filter.test(context)) continue;
            String display = context.name();
            if (context.isSandbox() && sender instanceof Player p) {
                if (!p.getUniqueId().equals(context.ownerUuid())) continue;
                display = OBUContextManager.displayName(display);
            }
            shown.add(display);
        }
        return CommandHelper.suggestMatching(builder, shown);
    }

    public static @NonNull Component targetName(@NonNull Wake plugin, @NonNull Entity target, @NonNull CommandSender sender) {
        if (target instanceof Player p) {
            return p.equals(sender) ? plugin.getMessageManager().getComponent("words.target.self") : Component.text(p.getName());
        }
        if (target instanceof Boat) {
            return plugin.getMessageManager().getComponent("words.target.boat");
        }
        return Component.text(target.getName());
    }

    public static @NonNull Component targetPossessive(@NonNull Wake plugin, @NonNull Entity target, @NonNull CommandSender sender) {
        if (target instanceof Player p) {
            return p.equals(sender)
                    ? plugin.getMessageManager().getComponent("words.target.self_possessive")
                    : plugin.getMessageManager().getComponent("words.target.other_possessive", Placeholder.unparsed("name", p.getName()));
        }
        if (target instanceof Boat) {
            return plugin.getMessageManager().getComponent("words.target.boat_possessive");
        }
        return plugin.getMessageManager().getComponent("words.target.other_possessive", Placeholder.unparsed("name", target.getName()));
    }
}
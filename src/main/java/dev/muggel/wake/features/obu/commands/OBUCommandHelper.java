package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.protocol.SettingType;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
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
import java.util.List;
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

    public static @NonNull List<String> displayArgs(@NonNull OBUSetting setting) {
        List<SettingType> types = setting.definition().types();
        List<String> args = setting.args();
        List<String> shown = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            boolean list = i < types.size() && types.get(i).isList();
            shown.add(list ? args.get(i).replace(",", ", ") : args.get(i));
        }
        return shown;
    }

    public static @NonNull Component displayValue(@NonNull Wake plugin, @NonNull OBUSetting setting) {
        List<SettingType> types = setting.definition().types();
        List<String> args = setting.args();
        TextComponent.Builder value = Component.text();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                value.append(Component.text(", "));
            }
            String arg = args.get(i);
            List<String> entries = i < types.size() && types.get(i).isList() ? List.of(arg.split(",")) : List.of();
            value.append(entries.size() > 1 ? collapsed(plugin, entries) : Component.text(arg));
        }
        return value.build();
    }

    private static @NonNull Component collapsed(@NonNull Wake plugin, @NonNull List<String> entries) {
        Component hover = plugin.getMessageManager().getComponent("commands.obu.status.collapsed_header", Placeholder.unparsed("count", String.valueOf(entries.size())));
        for (String entry : entries) {
            hover = hover.append(Component.newline()).append(plugin.getMessageManager().getComponent("commands.obu.status.collapsed_entry", Placeholder.unparsed("entry", entry)));
        }
        return plugin.getMessageManager().getComponent("commands.obu.status.collapsed_count", Placeholder.unparsed("count", String.valueOf(entries.size()))).hoverEvent(HoverEvent.showText(hover));
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
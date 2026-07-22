package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class OBUCommandHelper {
    private OBUCommandHelper() {}

    public static @NonNull OBUModule module(@NonNull Wake plugin) {
        OBUModule module = plugin.getModule(OBUModule.class);
        if (module == null) {
            throw new IllegalStateException("OBU module is not loaded");
        }
        return module;
    }

    public static @NonNull OBUServiceImpl service(@NonNull Wake plugin) {
        OBUServiceImpl service = module(plugin).getObuService();
        if (service == null) {
            throw new IllegalStateException("OBU module is not loaded");
        }
        return service;
    }

    public static @NonNull OBUContextManager contexts(@NonNull Wake plugin) {
        OBUContextManager contextManager = module(plugin).getContextManager();
        if (contextManager == null) {
            throw new IllegalStateException("OBU module is not loaded");
        }
        return contextManager;
    }

    public static @NonNull CompletableFuture<Suggestions> suggestContexts(
            @NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder,
            @NonNull Wake plugin, @NonNull Predicate<OBUContext> filter) {
        OBUContextManager contextManager = contexts(plugin);
        CommandSender sender = ctx.getSource().getSender();
        List<String> shown = new ArrayList<>();
        for (String name : contextManager.getContextNames()) {
            OBUContext context = contextManager.getContext(name);
            if (context == null || !filter.test(context)) continue;
            String display = name;
            if (context.isSandbox() && sender instanceof Player p) {
                if (!p.getUniqueId().equals(context.ownerUuid())) continue;
                display = OBUContextManager.displayName(name);
            }
            shown.add(display);
        }
        return CommandHelper.suggestMatching(builder, shown);
    }

    public static @NonNull Component targetName(@NonNull Wake plugin, Entity target, CommandSender sender) {
        if (target instanceof Player p) {
            return p.equals(sender) ? plugin.getMessageManager().getComponent("words.target.self") : Component.text(p.getName());
        }
        if (target instanceof Boat) {
            return plugin.getMessageManager().getComponent("words.target.boat");
        }
        return Component.text(target.getName());
    }

    public static @NonNull Component targetPossessive(@NonNull Wake plugin, Entity target, CommandSender sender) {
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
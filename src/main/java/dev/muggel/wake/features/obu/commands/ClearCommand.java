package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.protocol.SettingMerge;
import dev.muggel.wake.features.obu.protocol.SettingMerge.Removal;
import dev.muggel.wake.features.obu.protocol.SettingSelector;
import dev.muggel.wake.features.obu.protocol.SettingType;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class ClearCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode clear = CommandNode.literal("-clear").withHelpKey("commands.obu.help.clear");
        for (OBUDefinition def : OBUDefinition.values()) {
            if (def.isOneShot()) {
                continue;
            }
            clear.addSubcommand(settingNode(def, plugin));
        }
        clear.addSubcommand(CommandNode.argument("key", NameArgumentType.greedy())
                .executesEntityOrAimedBoat((ctx, target) -> executeKey(ctx, target, plugin)));
        return clear;
    }

    private static @NonNull CommandNode settingNode(@NonNull OBUDefinition def, Wake plugin) {
        CommandNode literal = CommandNode.literal(def.commandName())
                .executesEntityOrAimedBoat((ctx, target) -> execute(ctx, target, plugin, def, List.of()));
        String[] argNames = OBUCommandHelper.argNames(def);
        List<SettingType> types = def.types();
        List<CommandNode> chain = new ArrayList<>();
        List<String> bound = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (!types.get(i).isIdentity()) {
                continue;
            }
            List<String> before = List.copyOf(bound);
            bound.add(argNames[i]);
            List<String> here = List.copyOf(bound);
            chain.add(CommandNode.argument(argNames[i], types.get(i).argument())
                    .suggests((ctx, builder) -> OBUCommandHelper.suggestNarrowing(ctx, builder, plugin, def, before))
                    .executesEntityOrAimedBoat((ctx, target) -> execute(ctx, target, plugin, def, here)));
        }
        return literal.arguments(chain.toArray(new CommandNode[0]));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Entity target, Wake plugin, @NonNull OBUDefinition def, @NonNull List<String> boundArgs) {
        return clear(plugin, ctx.getSource().getSender(), target, SettingSelector.of(def, OBUCommandHelper.parsedArgs(ctx, boundArgs)), def.name());
    }

    private static int executeKey(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull Entity target, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String key = StringArgumentType.getString(ctx, "key");
        SettingSelector selector = SettingSelector.ofKey(key);
        if (selector == null) {
            OBUDefinition oneShot = OBUDefinition.byName(key);
            if (oneShot == null) {
                plugin.getMessageManager().send(sender, "commands.obu.clear.unknown", Placeholder.unparsed("setting", key));
                return 0;
            }
            selector = SettingSelector.of(oneShot, List.of());
        }
        return clear(plugin, sender, target, selector, selector.target().name());
    }

    private static int clear(Wake plugin, @NonNull CommandSender sender, @NonNull Entity target, @NonNull SettingSelector selector, @NonNull String settingName) {
        String narrowed = selector.identity().isEmpty()
                ? settingName
                : settingName + " " + String.join(" ", selector.identity());
        Removal removal = OBUCommandHelper.delivery(plugin).removeSettings(target, selector);
        String sandbox = target instanceof Player player
                ? OBUCommandHelper.active(plugin).sandboxOf(player.getUniqueId())
                : null;
        if (removal != null && !removal.taken().isEmpty()) {
            if (sandbox != null) {
                plugin.getMessageManager().send(sender, "commands.obu.clear.sandbox",
                        Placeholder.unparsed("setting", narrowed),
                        Placeholder.component("value", OBUCommandHelper.tookEntries(plugin, removal.removed())),
                        Placeholder.unparsed("sandbox", OBUContextManager.displayName(sandbox)));
            } else {
                plugin.getMessageManager().send(sender, "commands.obu.clear.temp",
                        Placeholder.unparsed("setting", narrowed),
                        Placeholder.component("value", OBUCommandHelper.tookEntries(plugin, removal.removed())),
                        Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
            }
            return Command.SINGLE_SUCCESS;
        }
        if (sandbox == null && target instanceof Player player && OBUCommandHelper.inBaseContext(plugin, player, held -> SettingMerge.takesFrom(held, selector))) {
            plugin.getMessageManager().send(sender, "commands.obu.clear.base_blocked", Placeholder.unparsed("setting", narrowed));
            return 0;
        }
        if (selector.exactKey() != null) {
            plugin.getMessageManager().send(sender, "commands.obu.clear.unknown", Placeholder.unparsed("setting", selector.exactKey()));
            return 0;
        }
        plugin.getMessageManager().send(sender, "commands.obu.clear.missing",
                Placeholder.unparsed("setting", narrowed),
                Placeholder.component("target", OBUCommandHelper.targetPossessive(plugin, target, sender)));
        return 0;
    }
}
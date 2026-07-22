package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DefaultsCommand {
    public static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("-defaults")
                .arguments(CommandNode.argument("setting", StringArgumentType.string())
                        .suggests(DefaultsCommand::suggestSetting)
                        .executesSender((ctx, sender) -> execute(ctx, plugin)));
    }

    private static @NonNull CompletableFuture<Suggestions> suggestSetting(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return CommandHelper.suggestMatching(builder,
                Arrays.stream(OBUDefinition.values()).map(OBUDefinition::commandName).toList());
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        String settingName = StringArgumentType.getString(ctx, "setting");
        OBUDefinition def = OBUDefinition.get(settingName);
        if (def == null || def.defaultValues() == null) {
            plugin.getMessageManager().send(sender, "commands.obu.defaults.missing", Placeholder.unparsed("setting", settingName));
            return 0;
        }
        String defValueStr = String.join(" ", def.defaultValues());
        plugin.getMessageManager().send(sender, "commands.obu.defaults.vanilla",
                Placeholder.parsed("setting", def.commandName()),
                Placeholder.parsed("value", defValueStr));
        if (!(sender instanceof Player player)) {
            return Command.SINGLE_SUCCESS;
        }
        OBUServiceImpl service = OBUCommandHelper.service(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String sandboxName = service.getPlayerActiveSandbox(player);
        String baseName = service.getActiveContextName(player);
        Map<String, OBUSetting> overrides = service.getSyncManager().getLocalOverrides(player.getUniqueId());
        OBUSetting effectiveSetting;
        boolean isServerDefault = false;
        int id = def.id();
        effectiveSetting = overrides.values().stream().filter(s -> s.definition().id() == id).findFirst().orElse(null);
        if (effectiveSetting == null && sandboxName != null) {
            OBUContext sb = contextManager.getContext(sandboxName);
            if (sb != null) {
                for (OBUSetting s : sb.settings()) {
                    if (s.definition().id() == id) {
                        effectiveSetting = s;
                        break;
                    }
                }
            }
        }
        if (effectiveSetting == null && baseName != null) {
            OBUContext base = contextManager.getContext(baseName);
            if (base != null) {
                for (OBUSetting s : base.settings()) {
                    if (s.definition().id() == id) {
                        effectiveSetting = s;
                        isServerDefault = true;
                        break;
                    }
                }
            }
        }
        if (effectiveSetting == null && sandboxName == null && baseName != null
                && !baseName.equalsIgnoreCase("default") && !baseName.equals(OBUDefinition.CONTEXT_EMPTY)) {
            OBUContext defaults = contextManager.getContext("default");
            if (defaults != null) {
                for (OBUSetting s : defaults.settings()) {
                    if (s.definition().id() == id) {
                        effectiveSetting = s;
                        isServerDefault = true;
                        break;
                    }
                }
            }
        }
        if (effectiveSetting != null) {
            String activeValue = String.join(", ", effectiveSetting.args());
            Component button;
            if (isServerDefault && sandboxName == null) {
                button = plugin.getMessageManager().getComponent("commands.obu.defaults.blocked_btn");
            } else {
                button = plugin.getMessageManager().getComponent("commands.obu.defaults.clear_btn", Placeholder.parsed("setting", def.commandName()));
            }
            plugin.getMessageManager().send(sender, "commands.obu.defaults.custom",
                    Placeholder.unparsed("value", activeValue),
                    Placeholder.component("button", button));
        } else {
            plugin.getMessageManager().send(sender, "commands.obu.defaults.active",
                    Placeholder.parsed("value", defValueStr));
        }
        return Command.SINGLE_SUCCESS;
    }
}
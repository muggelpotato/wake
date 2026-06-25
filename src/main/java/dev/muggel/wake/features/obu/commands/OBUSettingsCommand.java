package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.features.obu.commands.arguments.BlockListArgumentType;
import dev.muggel.wake.features.obu.commands.arguments.EntityListArgumentType;
import dev.muggel.wake.features.obu.commands.arguments.OBUEnumArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.WakeCommandBuilder;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.commands.util.OBUCommandBuilder;
import dev.muggel.wake.features.obu.context.OBUSetting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class OBUSettingsCommand {

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root, Wake plugin) {
        for (String settingName : OBUDefinition.getRegisteredNames()) {
            OBUDefinition def = OBUDefinition.get(settingName);
            if (def != null) {
                registerSettingCommand(root, def, plugin);
            }
        }
    }

    private static void registerSettingCommand(LiteralArgumentBuilder<CommandSourceStack> parentNode, @NonNull OBUDefinition def, Wake plugin) {
        LiteralArgumentBuilder<CommandSourceStack> settingNode = WakeCommandBuilder.literal(def.name(), def.getPermission());

        List<String> types = def.types();
        String[] argNames = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            String t = types.get(i);
            int count = 1;
            for (int j = 0; j < i; j++) if (types.get(j).equals(t)) count++;
            argNames[i] = count > 1 ? t + count : t;
        }

        if (types.isEmpty()) {
            settingNode.executes(OBUCommandBuilder.executesEntity(plugin, (ctx, target, service, contextManager) -> 
                    executeSetting(ctx, def, types, argNames, target, service, plugin)));
        } else {
            ArgumentBuilder<CommandSourceStack, ?> lastNode = buildArgumentNode(types.getLast(), argNames[types.size() - 1], plugin);
            lastNode.executes(OBUCommandBuilder.executesEntity(plugin, (ctx, target, service, contextManager) -> 
                    executeSetting(ctx, def, types, argNames, target, service, plugin)));

            for (int i = types.size() - 2; i >= 0; i--) {
                RequiredArgumentBuilder<CommandSourceStack, ?> prevNode = buildArgumentNode(types.get(i), argNames[i], plugin);
                prevNode.then(lastNode);
                lastNode = prevNode;
            }

            settingNode.then(lastNode);
        }

        parentNode.then(settingNode);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> buildArgumentNode(@NonNull String type, String name, Wake plugin) {
        return switch (type.toLowerCase()) {
            case "boolean" -> Commands.argument(name, BoolArgumentType.bool());
            case "float" -> Commands.argument(name, FloatArgumentType.floatArg());
            case "double" -> Commands.argument(name, DoubleArgumentType.doubleArg());
            case "int" -> Commands.argument(name, IntegerArgumentType.integer());
            case "byte" -> Commands.argument(name, IntegerArgumentType.integer(0, 255));
            case "block_list" -> Commands.argument(name, BlockListArgumentType.blockList());
            case "entity_list" -> Commands.argument(name, EntityListArgumentType.entityList());
            case "collision_enum" ->
                    Commands.argument(name, OBUEnumArgumentType.obuEnum(OBUDefinition.CollisionMode.class));
            case "setting_enum" ->
                    Commands.argument(name, OBUEnumArgumentType.obuEnum(OBUDefinition.PerBlockSetting.class));
            case "context_id" -> Commands.argument(name, StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        String remaining = builder.getRemaining().toLowerCase();
                        OBUModule module = plugin.getModule(OBUModule.class);
                        if (module != null) {
                            module.getContextManager().getContextNames().stream()
                                    .filter(p -> p.toLowerCase().startsWith(remaining))
                                    .forEach(builder::suggest);
                        }
                        return builder.buildFuture();
                    });
            default -> Commands.argument(name, StringArgumentType.string());
        };
    }

    private static int executeSetting(CommandContext<CommandSourceStack> ctx, OBUDefinition def, @NonNull List<String> types, String[] argNames, Entity target, OBUService obuService, Wake plugin) {
        String[] args = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            Object argVal = ctx.getArgument(argNames[i], Object.class);
            args[i] = String.valueOf(argVal);
        }

        OBUSetting setting = new OBUSetting(def, args);

        CommandSender sender = ctx.getSource().getSender();

        if (def.id() == 0 && def.channel().equals("settings")) {
            if (target instanceof Player p) {
                obuService.applyDefaultContext(p);
                plugin.getMessageManager().send(sender, "commands.obu.settings.reset");
            } else if (target instanceof Boat b) {
                obuService.getSyncManager().clearLocalOverrides(b.getUniqueId());
                obuService.getSyncManager().broadcastSync(b);
                plugin.getMessageManager().send(sender, "commands.obu.settings.success",
                        Placeholder.parsed("setting", def.name()),
                        Placeholder.parsed("value", ""),
                        Placeholder.parsed("target", "the boat"));
            }
            return Command.SINGLE_SUCCESS;
        }

        if (!obuService.applySetting(target, setting)) {
            plugin.getMessageManager().send(sender, "commands.obu.context.invalid_target");
            return 0;
        }

        String valueStr = String.join(" ", args);
        String sandbox = null;
        if (target instanceof Player p) {
            sandbox = obuService.getPlayerActiveSandbox(p);
        }
        if (sandbox != null && def.isContextSetting()) {
            plugin.getMessageManager().send(sender, "commands.obu.settings.sandbox",
                    Placeholder.parsed("setting", def.name()),
                    Placeholder.parsed("value", valueStr),
                    Placeholder.parsed("sandbox", sandbox));
        } else {
            String targetStr = target instanceof Player p ? (p.equals(sender) ? "you" : p.getName()) : (target instanceof Boat ? "the boat" : target.getName());
            plugin.getMessageManager().send(sender, "commands.obu.settings.success",
                    Placeholder.parsed("setting", def.name()),
                    Placeholder.parsed("value", valueStr),
                    Placeholder.parsed("target", targetStr));
        }

        return Command.SINGLE_SUCCESS;
    }
}

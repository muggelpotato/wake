package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.BlockListArgumentType;
import dev.muggel.wake.core.commands.arguments.EntityListArgumentType;
import dev.muggel.wake.core.commands.arguments.WakeEnumArgumentType;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUSetting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

public class OBUSettingsCommand {

    public static @NonNull List<CommandNode> getNodes(Wake plugin) {
        List<CommandNode> nodes = new ArrayList<>();
        for (String settingName : OBUDefinition.getRegisteredNames()) {
            OBUDefinition def = OBUDefinition.get(settingName);
            if (def != null) {
                nodes.add(createSettingNode(def, plugin));
            }
        }
        return nodes;
    }

    private static @NonNull CommandNode createSettingNode(@NonNull OBUDefinition def, Wake plugin) {
        CommandNode settingNode = CommandNode.literal(def.name())
                .withModule(OBUModule.class)
                .withPermission(def.getPermission());

        List<String> types = def.types();
        String[] argNames = new String[types.size()];
        for (int i = 0; i < types.size(); i++) {
            String t = types.get(i);
            int count = 1;
            for (int j = 0; j < i; j++) if (types.get(j).equals(t)) count++;
            argNames[i] = count > 1 ? t + count : t;
        }

        if (types.isEmpty()) {
            settingNode.executesEntity((ctx, target) -> {
                OBUModule obuModule = plugin.getModule(OBUModule.class);
                if (obuModule == null) return 0;
                return executeSetting(ctx, def, types, argNames, target, obuModule.getObuService(), plugin);
            });
        } else {
            List<CommandNode> argNodes = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                argNodes.add(buildArgumentNode(types.get(i), argNames[i], plugin));
            }

            argNodes.getLast().executesEntity((ctx, target) -> {
                OBUModule obuModule = plugin.getModule(OBUModule.class);
                if (obuModule == null) return 0;
                return executeSetting(ctx, def, types, argNames, target, obuModule.getObuService(), plugin);
            });

            for (int i = 0; i < argNodes.size() - 1; i++) {
                argNodes.get(i).addSubcommand(argNodes.get(i + 1));
            }

            settingNode.addSubcommand(argNodes.getFirst());
        }

        return settingNode;
    }

    private static @NonNull CommandNode buildArgumentNode(@NonNull String type, String name, Wake plugin) {
        CommandNode node = switch (type.toLowerCase()) {
            case "boolean" -> CommandNode.argument(name, BoolArgumentType.bool());
            case "float" -> CommandNode.argument(name, FloatArgumentType.floatArg());
            case "double" -> CommandNode.argument(name, DoubleArgumentType.doubleArg());
            case "int" -> CommandNode.argument(name, IntegerArgumentType.integer());
            case "byte" -> CommandNode.argument(name, IntegerArgumentType.integer(0, 255));
            case "block_list" -> CommandNode.argument(name, BlockListArgumentType.blockList());
            case "entity_list" -> CommandNode.argument(name, EntityListArgumentType.entityList());
            case "collision_enum" ->
                    CommandNode.argument(name, WakeEnumArgumentType.wakeEnum(OBUDefinition.CollisionMode.class));
            case "setting_enum" ->
                    CommandNode.argument(name, WakeEnumArgumentType.wakeEnum(OBUDefinition.PerBlockSetting.class));
            case "context_id" -> CommandNode.argument(name, StringArgumentType.string())
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
            default -> CommandNode.argument(name, StringArgumentType.string());
        };
        node.withModule(OBUModule.class);
        return node;
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
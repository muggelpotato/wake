package dev.muggel.wake.core.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@SuppressWarnings({"unchecked", "rawtypes"})
public class WakeCommandManager {
    private static final Map<String, CommandNode> REGISTERED_NODES = new ConcurrentHashMap<>();

    public static void register(CommandNode rootNode) {
        REGISTERED_NODES.put(rootNode.getName().toLowerCase(Locale.ROOT), rootNode);
    }

    public static void unregister(@NonNull String commandName) {
        REGISTERED_NODES.remove(commandName.toLowerCase(Locale.ROOT));
    }

    public static void init(@NonNull Wake plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            for (CommandNode node : REGISTERED_NODES.values()) {
                compileAndRegister(plugin, commands, node);
            }
        });
    }

    private static void compileAndRegister(Wake plugin, Commands commands, @NonNull CommandNode rootNode) {
        Class<? extends WakeModule> moduleClass = rootNode.getModuleClass();
        String moduleName = "base";
        if (moduleClass != null) {
            WakeModule module = plugin.getModule(moduleClass);
            if (module != null) {
                moduleName = module.getId();
            } else {
                moduleName = moduleClass.getSimpleName().toLowerCase().replace("module", "");
            }
        }

        String basePermission = rootNode.getCustomPermission() != null ? 
                rootNode.getCustomPermission() : "wake." + moduleName + ".commands";

        if (!rootNode.isArgument()) {
            LiteralArgumentBuilder<CommandSourceStack> literalRoot = compileLiteralNode(plugin, rootNode, basePermission, moduleClass);
            commands.register(literalRoot.build(), rootNode.getDescription(), rootNode.getAliases());
        }
    }

    public static @NonNull LiteralArgumentBuilder<CommandSourceStack> compileLiteralNode(
            Wake plugin, 
            CommandNode node, 
            String currentPermissionPath, 
            Class<? extends WakeModule> parentModuleClass) {
        return (LiteralArgumentBuilder<CommandSourceStack>) compileNode(plugin, node, currentPermissionPath, parentModuleClass, true);
    }

    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> compileNode(
            Wake plugin,
            @NonNull CommandNode node,
            String currentPermissionPath,
            Class<? extends WakeModule> parentModuleClass,
            boolean isRoot) {

        Class<? extends WakeModule> effectiveModuleClass = node.getModuleClass() != null ? node.getModuleClass() : parentModuleClass;
        
        String cleanName = node.getName().startsWith("-") ? node.getName().substring(1) : node.getName();
        String nodePermission;
        if (node.getCustomPermission() != null) {
            nodePermission = node.getCustomPermission();
        } else if (isRoot || node.isArgument()) {
            nodePermission = currentPermissionPath;
        } else {
            nodePermission = currentPermissionPath + "." + cleanName;
        }

        PermissionManager.registerPermission(nodePermission);

        ArgumentBuilder builder;
        if (node.isArgument()) {
            RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = Commands.argument(node.getName(), node.getArgumentType());
            if (node.getCustomSuggester() != null) {
                argBuilder.suggests(node.getCustomSuggester());
            }
            builder = argBuilder;
        } else {
            builder = Commands.literal(node.getName());
        }

        builder.requires(source -> {
            CommandSourceStack css = (CommandSourceStack) source;
            boolean hasPerm = PermissionManager.hasAccess(css.getSender(), nodePermission);
            if (!hasPerm) return false;
            if (effectiveModuleClass != null) {
                return plugin.getModule(effectiveModuleClass) != null;
            }
            return true;
        });

        if (node.getExecutor() != null) {
            builder.executes(ctx -> {
                CommandSourceStack source = (CommandSourceStack) ctx.getSource();
                CommandSender sender = source.getSender();

                if (effectiveModuleClass != null && plugin.getModule(effectiveModuleClass) == null) {
                    String moduleName = effectiveModuleClass.getSimpleName().replace("Module", "");
                    plugin.getMessageManager().send(sender, "commands.module_not_loaded", Placeholder.parsed("module", moduleName));
                    return 0;
                }

                Object target = null;
                switch (node.getTargetType()) {
                    case SENDER -> target = sender;
                    case PLAYER -> {
                        if (!(sender instanceof Player p)) {
                            plugin.getMessageManager().send(sender, "commands.only_players");
                            return 0;
                        }
                        target = p;
                    }
                    case ENTITY -> {
                        Entity t = source.getExecutor();
                        if (t == null) {
                            if (sender instanceof Entity e) {
                                t = e;
                            } else {
                                plugin.getMessageManager().send(sender, "commands.only_entities");
                                return 0;
                            }
                        }
                        if (t instanceof Player p) {
                            Entity rayTraceTarget = p.getTargetEntity(16);
                            if (rayTraceTarget instanceof Boat boat) {
                                t = boat;
                            }
                        }
                        target = t;
                    }
                    case ENTITY_NO_SMART -> {
                        Entity t = source.getExecutor();
                        if (t == null) {
                            if (sender instanceof Entity e) {
                                t = e;
                            } else {
                                plugin.getMessageManager().send(sender, "commands.only_entities");
                                return 0;
                            }
                        }
                        target = t;
                    }
                }

                try {
                    return node.getExecutor().execute(source, target, (CommandContext<CommandSourceStack>) ctx);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing command: " + node.getName(), e);
                    return 0;
                }
            });
        }

        for (CommandNode child : node.getChildren()) {
            ArgumentBuilder childCompiled = compileNode(plugin, child, nodePermission, effectiveModuleClass, false);
            builder.then(childCompiled);
        }

        return builder;
    }
}

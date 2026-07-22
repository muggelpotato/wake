package dev.muggel.wake.core.commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Compiles registered {@link CommandNode} trees into Paper/Brigadier commands. <br>
 * 1. Derives a permission from each chain of literals ({@code wake.<module>.commands.<literal>...}) <br>
 * 2. Hides and blocks commands whose module is disabled <br>
 * 3. Resolves the executor target ({@link CommandNode.TargetType}) <br>
 * 4. Wraps every executor with error handling and database actor tracking <br>
 * Modules only register a root node (everything else is automatic). <br>
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class WakeCommandManager {
    private static final Map<String, CommandNode> REGISTERED_NODES = new ConcurrentHashMap<>();

    public static void register(CommandNode rootNode) {
        REGISTERED_NODES.put(rootNode.getName().toLowerCase(Locale.ROOT), rootNode);
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
            WakeModule module = plugin.getRegisteredModule(moduleClass);
            if (module == null) {
                throw new IllegalStateException("Command '" + rootNode.getName() + "' is bound to unregistered module class " + moduleClass.getSimpleName());
            }
            moduleName = module.getId();
        }
        String basePermission = "wake." + moduleName + ".commands";
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
        Class<? extends WakeModule> nodeModuleClass = node.getModuleClass();
        Class<? extends WakeModule> effectiveModuleClass = nodeModuleClass != null ? nodeModuleClass : parentModuleClass;
        String cleanName = node.getName().startsWith("-") ? node.getName().substring(1) : node.getName();
        String nodePermission;
        if (isRoot || node.isArgument()) {
            nodePermission = currentPermissionPath;
        } else {
            nodePermission = currentPermissionPath + "." + cleanName;
        }
        PermissionManager.registerPermission(nodePermission);
        ArgumentBuilder builder;
        if (node.isArgument()) {
            RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = Commands.argument(node.getName(), node.getArgumentType());
            SuggestionProvider<CommandSourceStack> customSuggester = node.getCustomSuggester();
            if (customSuggester != null) {
                argBuilder.suggests(customSuggester);
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
        CommandNode.NodeExecutor executor = node.getExecutor();
        if (executor != null) {
            builder.executes(ctx -> {
                CommandSourceStack source = (CommandSourceStack) ctx.getSource();
                CommandSender sender = source.getSender();
                if (effectiveModuleClass != null && plugin.getModule(effectiveModuleClass) == null) {
                    WakeModule registered = plugin.getRegisteredModule(effectiveModuleClass);
                    String moduleName = registered != null ? registered.getId() : effectiveModuleClass.getSimpleName();
                    plugin.getMessageManager().send(sender, "commands.base.module_not_loaded", Placeholder.unparsed("module", moduleName));
                    return 0;
                }
                Object target = node.getTargetType().resolve(source, plugin);
                if (target == null) {
                    return 0;
                }
                UUID previousActor = plugin.getDatabaseManager().currentActor();
                try {
                    plugin.getDatabaseManager().setActor(sender);
                    return executor.execute(target, (CommandContext<CommandSourceStack>) ctx);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing command: " + node.getName(), e);
                    plugin.getMessageManager().send(sender, "commands.error");
                    return 0;
                } finally {
                    plugin.getDatabaseManager().restoreActor(previousActor);
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
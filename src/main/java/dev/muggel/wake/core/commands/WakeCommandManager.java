package dev.muggel.wake.core.commands;

import com.mojang.brigadier.arguments.ArgumentType;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Compiles registered {@link CommandNode} trees into Paper/Brigadier commands. <br>
 * 1. Derives a permission from each chain of literals ({@code wake.<module>.commands.<literal>...}) and files it under whichever {@link PermissionPreset} bundles the node declared <br>
 * 2. Hides and blocks commands whose module is disabled <br>
 * 3. Resolves the executor target ({@link CommandNode.TargetType}) and applies the inherited {@link CommandNode.Gate} <br>
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
            PermissionManager.registerPresets();
        });
    }

    public static @NonNull List<CommandNode> getRegisteredRoots() {
        List<CommandNode> roots = new ArrayList<>(REGISTERED_NODES.values());
        roots.sort(Comparator.comparing(CommandNode::getName));
        return roots;
    }

    /** The compiled root a module registered, for reading its sub-commands back (their permissions included) */
    public static @Nullable CommandNode rootOf(@NonNull Class<? extends WakeModule> moduleClass) {
        for (CommandNode root : REGISTERED_NODES.values()) {
            if (root.getModuleClass() == moduleClass) {
                return root;
            }
        }
        return null;
    }

    public static @Nullable String moduleIdOf(Wake plugin, @NonNull CommandNode rootNode) {
        Class<? extends WakeModule> moduleClass = rootNode.getModuleClass();
        if (moduleClass == null) {
            return "base";
        }
        WakeModule module = plugin.getRegisteredModule(moduleClass);
        return module != null ? module.getId() : null;
    }

    private static void compileAndRegister(Wake plugin, Commands commands, @NonNull CommandNode rootNode) {
        Class<? extends WakeModule> moduleClass = rootNode.getModuleClass();
        String moduleName = moduleIdOf(plugin, rootNode);
        if (moduleName == null) {
            throw new IllegalStateException("Command '" + rootNode.getName() + "' is bound to unregistered module class " + (moduleClass != null ? moduleClass.getSimpleName() : "?"));
        }
        String basePermission = "wake." + moduleName + ".commands";
        if (!rootNode.isArgument()) {
            LiteralArgumentBuilder<CommandSourceStack> literalRoot = (LiteralArgumentBuilder<CommandSourceStack>)
                    compileNode(plugin, rootNode, basePermission, new Inherited(moduleClass, Set.of(), null), true);
            commands.register(literalRoot.build(), rootNode.getDescription(), rootNode.getAliases());
        }
    }

    /** What a node takes from its parent unless it declares its own */
    private record Inherited(@Nullable Class<? extends WakeModule> moduleClass, @NonNull Set<PermissionPreset> presets, CommandNode.@Nullable Gate gate) {
        @NonNull Inherited resolve(@NonNull CommandNode node) {
            return new Inherited(
                    node.getModuleClass() != null ? node.getModuleClass() : moduleClass,
                    node.getBranchPresets() != null ? node.getBranchPresets() : presets,
                    node.getGate() != null ? node.getGate() : gate);
        }
    }

    @SuppressWarnings("ExtractMethodRecommender")
    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> compileNode(
            Wake plugin,
            @NonNull CommandNode node,
            String currentPermissionPath,
            @NonNull Inherited parent,
            boolean isRoot) {
        Inherited inherited = parent.resolve(node);
        Class<? extends WakeModule> effectiveModuleClass = inherited.moduleClass();
        String cleanName = node.getName().startsWith("-") ? node.getName().substring(1) : node.getName();
        String nodePermission;
        if (isRoot || node.isArgument()) {
            nodePermission = currentPermissionPath;
        } else {
            nodePermission = currentPermissionPath + "." + cleanName;
        }
        node.setPermission(nodePermission);
        PermissionManager.registerPermission(nodePermission);
        PermissionManager.assignPresets(nodePermission, inherited.presets());
        PermissionManager.assignPresets(nodePermission, node.getPresets());
        ArgumentType<?> argumentType = node.getArgumentType();
        ArgumentBuilder builder;
        if (argumentType != null) {
            RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = Commands.argument(node.getName(), argumentType);
            SuggestionProvider<CommandSourceStack> customSuggester = node.getCustomSuggester();
            if (customSuggester != null) {
                argBuilder.suggests(customSuggester);
            }
            builder = argBuilder;
        } else {
            builder = PermissionAwareLiteral.builder(node.getName());
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
                UUID previousActor = plugin.getDatabaseManager().currentActor();
                try {
                    Object target = node.getTargetType().resolve(source, plugin);
                    if (target == null) {
                        return 0;
                    }
                    CommandNode.Gate gate = inherited.gate();
                    if (gate != null && !gate.allows(source, target)) {
                        return 0;
                    }
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
            ArgumentBuilder childCompiled = compileNode(plugin, child, nodePermission, inherited, false);
            builder.then(childCompiled);
        }
        return builder;
    }
}
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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
public final class WakeCommandManager {
    private static final Map<String, CommandNode> REGISTERED_NODES = new ConcurrentHashMap<>();
    private static final Map<String, CommandNode> PERMISSION_OWNERS = new ConcurrentHashMap<>();
    private WakeCommandManager() {}

    public static void register(CommandNode rootNode) {
        CommandNode claimed = REGISTERED_NODES.put(rootNode.getName().toLowerCase(Locale.ROOT), rootNode);
        if (claimed != null && claimed != rootNode) {
            throw new IllegalStateException("Two modules both register the command /" + rootNode.getName());
        }
    }

    public static void init(@NonNull Wake plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            for (CommandNode node : REGISTERED_NODES.values()) {
                compileAndRegister(plugin, commands, node);
            }
            PermissionManager.sealPresets();
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
            String description = PlainTextComponentSerializer.plainText()
                    .serialize(CommandHelper.moduleDescription(plugin, moduleName, rootNode));
            commands.register(literalRoot.build(), description, rootNode.getAliases());
        }
    }

    /** What a node takes from its parent unless it declares its own */
    private record Inherited(@Nullable Class<? extends WakeModule> moduleClass, @NonNull Set<PermissionPreset> presets, CommandNode.@Nullable Gate gate) {
        @NonNull Inherited resolve(@NonNull CommandNode node) {
            return new Inherited(
                    node.getModuleClass() != null ? node.getModuleClass() : moduleClass,
                    presetsOf(node, presets),
                    node.getGate() != null ? node.getGate() : gate);
        }

        private static @NonNull Set<PermissionPreset> presetsOf(@NonNull CommandNode node, @NonNull Set<PermissionPreset> inherited) {
            Set<PermissionPreset> declared = node.getPresets();
            if (declared == null) {
                return inherited;
            }
            if (declared.isEmpty() || inherited.isEmpty()) {
                return declared;
            }
            EnumSet<PermissionPreset> joined = EnumSet.copyOf(inherited);
            joined.addAll(declared);
            return joined;
        }
    }

    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> compileNode(
            Wake plugin,
            @NonNull CommandNode node,
            String currentPermissionPath,
            @NonNull Inherited parent,
            boolean isRoot) {
        Inherited inherited = parent.resolve(node);
        Class<? extends WakeModule> module = inherited.moduleClass();
        String nodePermission = derivePermission(node, currentPermissionPath, isRoot);
        node.setPermission(nodePermission);
        if (!node.isArgument()) {
            claimPermission(node, nodePermission);
        }
        PermissionManager.registerPermission(nodePermission);
        PermissionManager.assignPresets(nodePermission, inherited.presets());
        ArgumentBuilder builder = builderFor(node);
        builder.requires(source -> PermissionManager.canReach(((CommandSourceStack) source).getSender(), nodePermission)
                && (module == null || plugin.getModule(module) != null));
        CommandNode.NodeExecutor executor = node.getExecutor();
        if (executor != null) {
            PermissionManager.markExecutable(nodePermission);
            builder.executes(ctx -> run(plugin, node, module, inherited.gate(), executor, (CommandContext<CommandSourceStack>) ctx));
        }
        for (CommandNode child : node.getChildren()) {
            builder.then(compileNode(plugin, child, nodePermission, inherited, false));
        }
        return builder;
    }

    /** {@code wake.<module>.commands.<literal>...} */
    private static @NonNull String derivePermission(@NonNull CommandNode node, String parentPath, boolean isRoot) {
        if (isRoot || node.isArgument()) {
            return parentPath;
        }
        String name = node.getName();
        return parentPath + "." + (name.startsWith("-") ? name.substring(1) : name);
    }

    private static void claimPermission(@NonNull CommandNode node, @NonNull String permission) {
        CommandNode claimedBy = PERMISSION_OWNERS.putIfAbsent(permission, node);
        if (claimedBy != null && claimedBy != node) {
            throw new IllegalStateException("Commands '" + claimedBy.getName() + "' and '" + node.getName() + "' both derive permission " + permission);
        }
    }

    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> builderFor(@NonNull CommandNode node) {
        ArgumentType<?> argumentType = node.getArgumentType();
        if (argumentType == null) {
            return PermissionAwareLiteral.builder(node.getName());
        }
        RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = Commands.argument(node.getName(), argumentType);
        SuggestionProvider<CommandSourceStack> suggester = node.getCustomSuggester();
        if (suggester != null) {
            argBuilder.suggests(suggester);
        }
        return argBuilder;
    }

    /** One command run start to finish */
    private static int run(Wake plugin, @NonNull CommandNode node, @Nullable Class<? extends WakeModule> module, CommandNode.@Nullable Gate gate, CommandNode.@NonNull NodeExecutor executor, @NonNull CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();
        if (module != null && plugin.getModule(module) == null) {
            WakeModule registered = plugin.getRegisteredModule(module);
            plugin.getMessageManager().send(sender, "commands.base.module_not_loaded",
                    Placeholder.unparsed("module", registered != null ? registered.getId() : module.getSimpleName()));
            return 0;
        }
        UUID previousActor = plugin.getDatabaseManager().currentActor();
        try {
            Object target = node.getTargetType().resolve(source, plugin);
            if (target == null) {
                return 0;
            }
            if (gate != null && !gate.allows(source, target)) {
                return 0;
            }
            plugin.getDatabaseManager().setActor(sender);
            return executor.execute(target, ctx);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error executing command: " + node.getName(), e);
            plugin.getMessageManager().send(sender, "commands.error");
            return 0;
        } finally {
            plugin.getDatabaseManager().restoreActor(previousActor);
        }
    }

}
package dev.muggel.wake.core.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.CommandSender;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Turns registered {@link CommandNode} trees into Paper/Brigadier commands <br>
 * {@link #declare} runs once at boot: derives every permission ({@code wake.<module>.commands.<literal>...}), files each node under the {@link PermissionPreset} bundles it declared, and rejects a malformed tree by throwing. <br>
 * {@link #init}'s handler only hands the already-derived trees to Brigadier and wraps each executor with target resolution, gating, error handling and database actor tracking. <br>
 * The tree is the one Wake declares, and a disabled module's commands are hidden by {@code requires} at query time.
 */
public final class WakeCommandManager {
    private static volatile List<CommandNode> roots = List.of();
    private static final Pattern LITERAL_NAME = Pattern.compile("-?[a-z0-9][a-z0-9_-]*");
    private WakeCommandManager() {}

    /** Derives and validates every module's command tree, keyed by the id of the module that built it. Called once at boot */
    public static void declare(@NonNull Map<String, CommandNode> declared) {
        Map<String, CommandNode> labels = new HashMap<>();
        Map<String, CommandNode> permissionOwners = new HashMap<>();
        declared.forEach((moduleId, root) -> {
            if (root.isArgument()) {
                throw new IllegalStateException("Command '/" + root.getName() + "' is an argument; a root must be a literal");
            }
            for (String label : allLabels(root)) {
                CommandNode claimed = labels.putIfAbsent(label, root);
                if (claimed != null) {
                    throw new IllegalStateException("The label '" + label + "' is claimed by both '/" + claimed.getName() + "' and '/" + root.getName() + "'");
                }
            }
            root.setModuleId(moduleId);
            derive(root, "wake." + moduleId + ".commands", Set.of(), true, permissionOwners);
        });
        PermissionManager.sealPresets();
        roots = declared.values().stream().sorted(Comparator.comparing(CommandNode::getName)).toList();
    }

    public static void init(@NonNull Wake plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            for (CommandNode root : roots) {
                LiteralArgumentBuilder<CommandSourceStack> literalRoot = PermissionAwareLiteral.builder(root.getName());
                wire(literalRoot, plugin, root, root, null);
                String description = PlainTextComponentSerializer.plainText()
                        .serialize(CommandHelper.moduleDescription(plugin, root));
                commands.register(literalRoot.build(), description, root.getAliases());
            }
        });
    }

    public static @NonNull @Unmodifiable List<CommandNode> rootsVisibleTo(@NonNull Wake plugin, @NonNull CommandSender sender) {
        return roots.stream().filter(root -> isVisible(plugin, root, root.getPermission(), sender)).toList();
    }

    /** The declared root a module registered, for reading its sub-commands back (their permissions included) */
    public static @Nullable CommandNode rootOf(@NonNull String moduleId) {
        for (CommandNode root : roots) {
            if (root.getModuleId().equals(moduleId)) {
                return root;
            }
        }
        return null;
    }

    /** Every command below a root owned by a module lives or dies with it and is hidden from whoever may not run it */
    private static boolean isVisible(@NonNull Wake plugin, @NonNull CommandNode root, @NonNull String permission, @NonNull CommandSender sender) {
        return plugin.isModuleActive(root.getModuleId()) && PermissionManager.canReach(sender, permission);
    }

    private static @NonNull List<String> allLabels(@NonNull CommandNode root) {
        List<String> labels = new ArrayList<>();
        labels.add(root.getName().toLowerCase(Locale.ROOT));
        for (String alias : root.getAliases()) {
            labels.add(alias.toLowerCase(Locale.ROOT));
        }
        return labels;
    }

    /** Walks the tree once, deriving each permission and checking everything that can only be wrong at boot */
    private static void derive(@NonNull CommandNode node, @NonNull String parentPath, @NonNull Set<PermissionPreset> inherited, boolean isRoot, @NonNull Map<String, CommandNode> permissionOwners) {
        List<CommandNode> children = node.getChildren();
        if (node.getExecutor() == null && children.isEmpty()) {
            throw new IllegalStateException("Node '" + node.getName() + "' under " + parentPath + " has neither an executor nor sub-commands");
        }
        if (!node.isArgument() && !LITERAL_NAME.matcher(node.getName()).matches()) {
            throw new IllegalStateException("Literal '" + node.getName() + "' under " + parentPath + " must match " + LITERAL_NAME.pattern() + ", or its permission would not be addressable");
        }
        String nodePermission = derivePermission(node, parentPath, isRoot);
        node.setPermission(nodePermission);
        if (!node.isArgument()) {
            CommandNode claimedBy = permissionOwners.putIfAbsent(nodePermission, node);
            if (claimedBy != null) {
                throw new IllegalStateException("Nodes '" + claimedBy.getName() + "' and '" + node.getName() + "' both derive permission " + nodePermission);
            }
        }
        PermissionManager.registerPermission(nodePermission);
        Set<PermissionPreset> presets = presetsOf(node, inherited);
        PermissionManager.assignPresets(nodePermission, presets);
        if (node.getExecutor() != null) {
            PermissionManager.markExecutable(nodePermission);
        }
        for (CommandNode child : children) {
            derive(child, nodePermission, presets, false, permissionOwners);
        }
    }

    /** {@code wake.<module>.commands.<literal>...} */
    private static @NonNull String derivePermission(@NonNull CommandNode node, @NonNull String parentPath, boolean isRoot) {
        if (isRoot || node.isArgument()) {
            return parentPath;
        }
        String name = node.getName();
        return parentPath + "." + (name.startsWith("-") ? name.substring(1) : name);
    }

    /** What a node takes from its parent unless it declares its own */
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

    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> compile(@NonNull Wake plugin, @NonNull CommandNode node, @NonNull CommandNode root, CommandNode.@Nullable Gate parentGate) {
        ArgumentBuilder<CommandSourceStack, ?> builder = builderFor(plugin, node);
        wire(builder, plugin, node, root, parentGate);
        return builder;
    }

    /** Hangs the permission check, the executor wrapper and the sub-commands off a builder */
    private static void wire(@NonNull ArgumentBuilder<CommandSourceStack, ?> builder, @NonNull Wake plugin, @NonNull CommandNode node, @NonNull CommandNode root, CommandNode.@Nullable Gate parentGate) {
        CommandNode.Gate gate = node.getGate() != null ? node.getGate() : parentGate;
        String nodePermission = node.getPermission();
        builder.requires(source -> isVisible(plugin, root, nodePermission, source.getSender()));
        CommandNode.NodeExecutor executor = node.getExecutor();
        if (executor != null) {
            builder.executes(ctx -> run(plugin, node, gate, executor, ctx));
        }
        for (CommandNode child : node.getChildren()) {
            builder.then(compile(plugin, child, root, gate));
        }
    }

    private static @NonNull ArgumentBuilder<CommandSourceStack, ?> builderFor(@NonNull Wake plugin, @NonNull CommandNode node) {
        ArgumentType<?> argumentType = node.getArgumentType();
        if (argumentType == null) {
            return PermissionAwareLiteral.builder(node.getName());
        }
        RequiredArgumentBuilder<CommandSourceStack, ?> argBuilder = Commands.argument(node.getName(), argumentType);
        SuggestionProvider<CommandSourceStack> suggester = node.getCustomSuggester();
        if (suggester != null) {
            argBuilder.suggests((ctx, builder) -> suggest(plugin, node, suggester, ctx, builder));
        }
        return argBuilder;
    }

    private static @NonNull CompletableFuture<Suggestions> suggest(@NonNull Wake plugin, @NonNull CommandNode node, @NonNull SuggestionProvider<CommandSourceStack> suggester, @NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder) {
        try {
            return suggester.getSuggestions(ctx, builder);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error suggesting for command: " + node.getName(), e);
            return Suggestions.empty();
        }
    }

    /** One command run start to finish */
    private static int run(@NonNull Wake plugin, @NonNull CommandNode node, CommandNode.@Nullable Gate gate, CommandNode.@NonNull NodeExecutor executor, @NonNull CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandSender sender = source.getSender();
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
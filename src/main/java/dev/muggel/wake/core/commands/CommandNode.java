package dev.muggel.wake.core.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for a node in Wake's command tree. <br>
 * A node is either a {@link #literal(String) literal} (fixed sub-command word) or an {@link #argument(String, ArgumentType) argument} (typed value). <br>
 * Children are attached with {@link #arguments(CommandNode...)} for a positional chain or {@link #addSubcommand(CommandNode)} for a branch. <br>
 * {@link WakeCommandManager} compiles the tree and derives each permission from the chain of literal names and owning module. <br>
 * An executor picks what it receives through the {@code executes...} flavor it is attached with (see {@link TargetType}). <br>
 * See the package documentation for the full command-authoring convention.
 */
public class CommandNode {
    /** How the framework resolves the object handed to an executor */
    public enum TargetType {
        /** player or console (never fails) */
        SENDER {
            @Override
            @NonNull Object resolve(@NonNull CommandSourceStack source, @NonNull Wake plugin) {
                return source.getSender();
            }
        },
        /** player required */
        PLAYER {
            @Override
            @Nullable Object resolve(@NonNull CommandSourceStack source, @NonNull Wake plugin) {
                if (source.getSender() instanceof Player player) {
                    return player;
                }
                plugin.getMessageManager().send(source.getSender(), "commands.only_players");
                return null;
            }
        },
        /** executing entity: sender itself, or entity behind {@code /execute as} */
        ENTITY {
            @Override
            @Nullable Object resolve(@NonNull CommandSourceStack source, @NonNull Wake plugin) {
                Entity executor = source.getExecutor();
                if (executor != null) {
                    return executor;
                }
                if (source.getSender() instanceof Entity entity) {
                    return entity;
                }
                plugin.getMessageManager().send(source.getSender(), "commands.only_entities");
                return null;
            }
        },
        /** like {@link #ENTITY}, player aiming at a boat targets that boat instead of themselves tho */
        ENTITY_OR_AIMED_BOAT {
            @Override
            @Nullable Object resolve(@NonNull CommandSourceStack source, @NonNull Wake plugin) {
                Object entity = ENTITY.resolve(source, plugin);
                if (entity instanceof Player player && player.getTargetEntity(AIM_DISTANCE) instanceof Boat boat) {
                    return boat;
                }
                return entity;
            }
        };

        private static final int AIM_DISTANCE = 16;

        abstract @Nullable Object resolve(@NonNull CommandSourceStack source, @NonNull Wake plugin);
    }

    @FunctionalInterface
    public interface CommandExecution<T> {
        @SuppressWarnings("RedundantThrows")
        int run(CommandContext<CommandSourceStack> ctx, T target) throws Exception;
    }

    @FunctionalInterface
    public interface NodeExecutor {
        int execute(Object target, CommandContext<CommandSourceStack> ctx) throws Exception;
    }

    private final String name;
    private final boolean isArgument;
    private final ArgumentType<?> argumentType;
    private final List<CommandNode> children = new ArrayList<>();
    private final List<String> aliases = new ArrayList<>();
    private String description = "";
    private Class<? extends WakeModule> moduleClass;
    private TargetType targetType = TargetType.SENDER;
    private NodeExecutor executor;
    private SuggestionProvider<CommandSourceStack> customSuggester;

    private CommandNode(String name, boolean isArgument, ArgumentType<?> argumentType) {
        this.name = name;
        this.isArgument = isArgument;
        this.argumentType = argumentType;
    }

    @Contract("_ -> new")
    public static @NonNull CommandNode literal(String name) {
        return new CommandNode(name, false, null);
    }

    @Contract("_, _ -> new")
    public static @NonNull CommandNode argument(String name, ArgumentType<?> argumentType) {
        return new CommandNode(name, true, argumentType);
    }

    public CommandNode withDescription(String description) {
        this.description = description;
        return this;
    }

    public CommandNode withModule(Class<? extends WakeModule> moduleClass) {
        this.moduleClass = moduleClass;
        return this;
    }

    public CommandNode aliases(String... aliases) {
        this.aliases.addAll(List.of(aliases));
        return this;
    }

    public CommandNode addSubcommand(CommandNode child) {
        this.children.add(child);
        return this;
    }

    public CommandNode arguments(CommandNode @NonNull ... chain) {
        CommandNode tail = this;
        for (CommandNode arg : chain) {
            tail.addSubcommand(arg);
            tail = arg;
        }
        return this;
    }

    public CommandNode suggests(SuggestionProvider<CommandSourceStack> provider) {
        this.customSuggester = provider;
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> CommandNode executes(TargetType targetType, CommandExecution<T> execution) {
        this.targetType = targetType;
        this.executor = (target, ctx) -> execution.run(ctx, (T) target);
        return this;
    }

    public CommandNode executesSender(CommandExecution<CommandSender> execution) {
        return executes(TargetType.SENDER, execution);
    }

    public CommandNode executesPlayer(CommandExecution<Player> execution) {
        return executes(TargetType.PLAYER, execution);
    }

    public CommandNode executesEntity(CommandExecution<Entity> execution) {
        return executes(TargetType.ENTITY, execution);
    }

    public CommandNode executesEntityOrAimedBoat(CommandExecution<Entity> execution) {
        return executes(TargetType.ENTITY_OR_AIMED_BOAT, execution);
    }

    public String getName() { return name; }
    public boolean isArgument() { return isArgument; }
    public ArgumentType<?> getArgumentType() { return argumentType; }
    public List<CommandNode> getChildren() { return children; }
    public List<String> getAliases() { return aliases; }
    public String getDescription() { return description; }
    public @Nullable Class<? extends WakeModule> getModuleClass() { return moduleClass; }
    public TargetType getTargetType() { return targetType; }
    public @Nullable NodeExecutor getExecutor() { return executor; }
    public @Nullable SuggestionProvider<CommandSourceStack> getCustomSuggester() { return customSuggester; }
}
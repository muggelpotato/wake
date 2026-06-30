package dev.muggel.wake.core.commands;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.muggel.wake.core.module.WakeModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class CommandNode {
    public enum TargetType {
        SENDER,
        PLAYER,
        ENTITY,
        ENTITY_NO_SMART
    }

    @FunctionalInterface
    public interface CommandExecution<T> {
        int run(CommandContext<CommandSourceStack> ctx, T target) throws Exception;
    }

    @FunctionalInterface
    public interface NodeExecutor {
        int execute(CommandSourceStack source, Object target, CommandContext<CommandSourceStack> ctx) throws Exception;
    }

    private final String name;
    private final boolean isArgument;
    private final ArgumentType<?> argumentType;
    private final List<CommandNode> children = new ArrayList<>();
    private final List<String> aliases = new ArrayList<>();
    private String description = "";
    private String customPermission;
    private Class<? extends WakeModule> moduleClass;
    private TargetType targetType = TargetType.SENDER;
    private NodeExecutor executor;
    private SuggestionProvider<CommandSourceStack> customSuggester;

    private CommandNode(String name, boolean isArgument, ArgumentType<?> argumentType) {
        this.name = name;
        this.isArgument = isArgument;
        this.argumentType = argumentType;
    }

    public static CommandNode literal(String name) {
        return new CommandNode(name, false, null);
    }

    public static CommandNode argument(String name, ArgumentType<?> argumentType) {
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

    public CommandNode withPermission(String permission) {
        this.customPermission = permission;
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

    public CommandNode suggests(SuggestionProvider<CommandSourceStack> provider) {
        this.customSuggester = provider;
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> CommandNode executes(TargetType targetType, CommandExecution<T> execution) {
        this.targetType = targetType;
        this.executor = (source, target, ctx) -> execution.run(ctx, (T) target);
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

    public CommandNode executesEntityNoSmart(CommandExecution<Entity> execution) {
        return executes(TargetType.ENTITY_NO_SMART, execution);
    }

    public String getName() { return name; }
    public boolean isArgument() { return isArgument; }
    public ArgumentType<?> getArgumentType() { return argumentType; }
    public List<CommandNode> getChildren() { return children; }
    public List<String> getAliases() { return aliases; }
    public String getDescription() { return description; }
    public String getCustomPermission() { return customPermission; }
    public Class<? extends WakeModule> getModuleClass() { return moduleClass; }
    public TargetType getTargetType() { return targetType; }
    public NodeExecutor getExecutor() { return executor; }
    public SuggestionProvider<CommandSourceStack> getCustomSuggester() { return customSuggester; }
}

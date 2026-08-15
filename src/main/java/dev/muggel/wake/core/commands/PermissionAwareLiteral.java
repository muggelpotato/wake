package dev.muggel.wake.core.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * A literal that hides itself from tab-completion for a sender that cannot run it. <br>
 * Brigadier filters the client's tree by {@code requires} but suggests from the unfiltered one, so a literal beside an argument with custom suggestions would otherwise leak
 */
final class PermissionAwareLiteral extends LiteralCommandNode<CommandSourceStack> {
    private PermissionAwareLiteral(String literal, @Nullable Command<CommandSourceStack> command, Predicate<CommandSourceStack> requirement, @Nullable CommandNode<CommandSourceStack> redirect, @Nullable RedirectModifier<CommandSourceStack> modifier, boolean forks) {
        super(literal, command, requirement, redirect, modifier, forks);
    }

    static @NonNull LiteralArgumentBuilder<CommandSourceStack> builder(String literal) {
        return new LiteralArgumentBuilder<>(literal) {
            @Override
            public LiteralCommandNode<CommandSourceStack> build() {
                LiteralCommandNode<CommandSourceStack> node = new PermissionAwareLiteral(
                        getLiteral(), getCommand(), getRequirement(), getRedirect(), getRedirectModifier(), isFork());
                for (CommandNode<CommandSourceStack> child : getArguments()) {
                    node.addChild(child);
                }
                return node;
            }
        };
    }

    @Override
    public @NonNull LiteralArgumentBuilder<CommandSourceStack> createBuilder() {
        LiteralArgumentBuilder<CommandSourceStack> rebuilt = builder(getLiteral());
        rebuilt.requires(getRequirement());
        rebuilt.forward(getRedirect(), getRedirectModifier(), isFork());
        if (getCommand() != null) {
            rebuilt.executes(getCommand());
        }
        return rebuilt;
    }

    @Override
    public CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<CommandSourceStack> context, @NonNull SuggestionsBuilder builder) {
        return canUse(context.getSource()) ? super.listSuggestions(context, builder) : Suggestions.empty();
    }
}
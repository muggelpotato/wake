package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.core.commands.arguments.NameArgumentType;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.contexts.OBUContext;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.OBUSyncManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class SandboxCommandHelper {
    private static final int MAX_SHARE_CODE_BYTES = 65536;
    private SandboxCommandHelper() {}

    static @NonNull TagResolver hint(@NonNull Wake plugin, @NonNull CommandSender subject) {
        return CommandHelper.hint(plugin, subject instanceof Player ? "commands.obu.sandbox.active_hint" : null);
    }

    static @Nullable OBUContext requireOwnSandbox(@NonNull Wake plugin, CommandSender sender, CommandSender subject, @NonNull String name) {
        String key = subject instanceof Player owner ? OBUContextManager.sandboxKey(name, owner.getUniqueId()) : name;
        OBUContext context = OBUCommandHelper.contexts(plugin).getContext(key);
        if (context == null || !context.isSandbox()) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.unparsed("sandbox", name));
            return null;
        }
        return context;
    }

    static @NonNull CommandNode nameArgument(String argumentName) {
        return CommandNode.argument(argumentName, NameArgumentType.greedy(OBUContextManager.NAME_PATTERN, "commands.obu.sandbox.invalid_name"));
    }

    static @NonNull CompletableFuture<Suggestions> suggestOwnSandboxes(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin, OBUContext::isSandbox);
    }

    static void enterSandbox(@NonNull Wake plugin, @NonNull Player player, @NonNull String key) {
        OBUSyncManager sync = OBUCommandHelper.sync(plugin);
        OBUCommandHelper.delivery(plugin).setPlayerActiveSandbox(player, key);
        sync.clearLocalOverrides(player.getUniqueId());
        sync.syncPlayer(player);
    }

    static @Nullable String claimSandbox(@NonNull Wake plugin, CommandSender sender, CommandSender subject, @NonNull String name) {
        if (OBUContextManager.isReserved(name)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.reserved", Placeholder.unparsed("sandbox", name));
            return null;
        }
        UUID owner = subject instanceof Player player ? player.getUniqueId() : null;
        String key = owner == null ? name : OBUContextManager.sandboxKey(name, owner);
        if (!OBUCommandHelper.contexts(plugin).createSandbox(key, owner)) {
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.unparsed("sandbox", name));
            return null;
        }
        return key;
    }

    static @NonNull String encodeShareCode(@NonNull String payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
    }

    static @NonNull String decodeShareCode(@NonNull String code) throws IOException {
        byte[] decoded = Base64.getUrlDecoder().decode(code);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len;
            int totalRead = 0;
            while ((len = gzip.read(buffer)) != -1) {
                totalRead += len;
                if (totalRead > MAX_SHARE_CODE_BYTES) {
                    throw new IOException("Decompressed payload exceeds maximum size of 64KB");
                }
                bos.write(buffer, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
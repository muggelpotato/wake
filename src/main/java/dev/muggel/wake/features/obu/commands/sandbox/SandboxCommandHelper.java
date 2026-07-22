package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandHelper;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import dev.muggel.wake.features.obu.service.OBUServiceImpl;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class SandboxCommandHelper {
    private static final Pattern SANDBOX_NAME_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int MAX_SHARE_CODE_BYTES = 65536;
    private SandboxCommandHelper() {}

    static void sendHintIfEnabled(@NonNull Wake plugin, @NonNull CommandSender sender) {
        CommandHelper.sendHint(plugin, sender, "commands.obu.sandbox.hint");
    }

    static String sandboxKeyFor(CommandSender sender, @NonNull String name) {
        return sender instanceof Player p ? OBUContextManager.sandboxKey(name, p.getUniqueId()) : name.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean isValidSandboxName(@NonNull String name) {
        return SANDBOX_NAME_PATTERN.matcher(name).matches();
    }

    static @NonNull CompletableFuture<Suggestions> suggestOwnSandboxes(@NonNull CommandContext<CommandSourceStack> ctx, @NonNull SuggestionsBuilder builder, Wake plugin) {
        return OBUCommandHelper.suggestContexts(ctx, builder, plugin, OBUContext::isSandbox);
    }

    static void enterSandbox(Player player, String name, @NonNull OBUServiceImpl service) {
        service.setPlayerActiveSandbox(player, name);
        service.resetPlayer(player);
        if (player.getVehicle() instanceof Boat boat) {
            service.broadcastBoatContext(boat);
        }
        service.getSyncManager().syncPlayer(player);
    }

    static @NonNull String encodeShareCode(@NonNull String payload) throws IOException {
        byte[] rawBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(rawBytes);
        }
        byte[] gzipBytes = baos.toByteArray();
        byte[] optimal = (gzipBytes.length < rawBytes.length) ? gzipBytes : rawBytes;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(optimal);
    }

    static @NonNull String decodeShareCode(@NonNull String code) throws IOException {
        byte[] decoded = Base64.getUrlDecoder().decode(code);
        if (decoded.length >= 2 && decoded[0] == (byte) 0x1f && decoded[1] == (byte) 0x8b) {
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
        if (decoded.length > MAX_SHARE_CODE_BYTES) {
            throw new IOException("Payload exceeds maximum size of 64KB");
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
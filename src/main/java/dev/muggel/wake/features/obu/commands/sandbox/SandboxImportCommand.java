package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class SandboxImportCommand {
    private static final int MAX_IMPORT_SETTINGS = 256;

    static @NonNull CommandNode getNode(Wake plugin) {
        return CommandNode.literal("import")
                .arguments(
                        CommandNode.argument("shareCode", StringArgumentType.string()),
                        SandboxCommandHelper.nameArgument("name")
                                .executesSender((ctx, subject) -> execute(ctx, subject, plugin)));
    }

    private static int execute(@NonNull CommandContext<CommandSourceStack> ctx, CommandSender subject, Wake plugin) {
        CommandSender sender = ctx.getSource().getSender();
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String code = StringArgumentType.getString(ctx, "shareCode");
        String decodedStr;
        try {
            decodedStr = SandboxCommandHelper.decodeShareCode(code);
        } catch (IOException | IllegalArgumentException badCode) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode share code", badCode);
            String reason = badCode.getMessage() != null ? badCode.getMessage() : badCode.getClass().getSimpleName();
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.import_fail", Placeholder.unparsed("error", reason));
            return 0;
        }
        String key = SandboxCommandHelper.claimSandbox(plugin, sender, subject, name);
        if (key == null) {
            return 0;
        }
        if (!decodedStr.isEmpty()) {
            int skipped = 0;
            List<OBUSetting> toImport = new ArrayList<>();
            for (String part : decodedStr.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                int colonIdx = part.indexOf(':');
                if (colonIdx == -1 || toImport.size() >= MAX_IMPORT_SETTINGS) {
                    skipped++;
                    continue;
                }
                try {
                    int id = Integer.parseInt(part.substring(0, colonIdx));
                    OBUDefinition def = OBUDefinition.byId(id);
                    OBUSetting setting = def == null || def.isOneShot()
                            ? null
                            : OBUSetting.of(def, def.splitInvocation(part.substring(colonIdx + 1)));
                    if (setting == null) {
                        skipped++;
                        continue;
                    }
                    toImport.add(setting);
                } catch (NumberFormatException e) {
                    skipped++;
                }
            }
            contextManager.addSettings(key, toImport);
            if (skipped > 0) {
                plugin.getMessageManager().send(sender, "commands.obu.sandbox.import_skipped",
                        Placeholder.unparsed("count", String.valueOf(skipped)));
            }
        }
        if (subject instanceof Player p) {
            SandboxCommandHelper.enterSandbox(plugin, p, key);
        }
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.imported", Placeholder.unparsed("sandbox", name), SandboxCommandHelper.hint(plugin, subject));
        return Command.SINGLE_SUCCESS;
    }
}
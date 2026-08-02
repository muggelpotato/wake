package dev.muggel.wake.features.obu.commands.sandbox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.protocol.OBUDefinition;
import dev.muggel.wake.features.obu.commands.OBUCommandHelper;
import dev.muggel.wake.features.obu.protocol.OBUSetting;
import dev.muggel.wake.features.obu.protocol.PacketWriter;
import dev.muggel.wake.features.obu.contexts.OBUContextManager;
import dev.muggel.wake.features.obu.delivery.ContextDelivery;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
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
        ContextDelivery service = OBUCommandHelper.delivery(plugin);
        OBUContextManager contextManager = OBUCommandHelper.contexts(plugin);
        String name = StringArgumentType.getString(ctx, "name");
        String code = StringArgumentType.getString(ctx, "shareCode");
        String decodedStr;
        try {
            decodedStr = SandboxCommandHelper.decodeShareCode(code);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to decode share code", e);
            String reason = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.import_fail", Placeholder.unparsed("error", reason));
            return 0;
        }
        String key = SandboxCommandHelper.claimSandbox(plugin, sender, subject, name, service);
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
                    String argsStr = part.substring(colonIdx + 1);
                    String[] args = argsStr.isEmpty() ? new String[0] : argsStr.split(" ");
                    OBUDefinition def = OBUDefinition.getById(id);
                    if (def == null || !def.isContextSetting()) {
                        skipped++;
                        continue;
                    }
                    OBUSetting setting = new OBUSetting(def, Arrays.asList(args));
                    if (!PacketWriter.isEncodable(setting)) {
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
        plugin.getMessageManager().send(sender, "commands.obu.sandbox.imported", Placeholder.unparsed("sandbox", name));
        if (subject instanceof Player p) {
            SandboxCommandHelper.enterSandbox(p, key, service, plugin);
            plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.unparsed("sandbox", name));
            SandboxCommandHelper.sendHintIfEnabled(plugin, sender);
        }
        return Command.SINGLE_SUCCESS;
    }
}
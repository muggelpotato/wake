package dev.muggel.wake.features.obu.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.CommandNode;
import dev.muggel.wake.features.obu.OBUDefinition;
import dev.muggel.wake.features.obu.OBUModule;
import dev.muggel.wake.features.obu.api.OBUService;
import dev.muggel.wake.features.obu.context.OBUContext;
import dev.muggel.wake.features.obu.context.OBUSetting;
import dev.muggel.wake.features.obu.service.OBUContextManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class OBUSandboxCommand {

    public static @NonNull CommandNode getNode(Wake plugin) {
        CommandNode sandboxNode = CommandNode.literal("-sandbox")
                .withModule(OBUModule.class);
        // create
        CommandNode createNode = CommandNode.literal("create").withModule(OBUModule.class);
        CommandNode createNameArg = CommandNode.argument("name", StringArgumentType.string())
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String name = StringArgumentType.getString(ctx, "name");
                    String lower = name.toLowerCase();

                    if (contextManager.getContextNames().contains(lower)) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    service.createSandbox(name);
                    if (sender instanceof Player player) {
                        enterSandbox(player, lower, service);
                    }
                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.created", Placeholder.parsed("sandbox", name));
                    if (plugin.getConfig().getBoolean("config.show_hints", true)) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.hint");
                    }
                    return Command.SINGLE_SUCCESS;
                });
        createNode.addSubcommand(createNameArg);
        sandboxNode.addSubcommand(createNode);
        // fork
        CommandNode forkNode = CommandNode.literal("fork").withModule(OBUModule.class);
        CommandNode forkLoadArg = CommandNode.argument("contextToLoad", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    OBUModule module = plugin.getModule(OBUModule.class);
                    if (module != null) {
                        module.getContextManager().getContextNames().stream()
                                .filter(name -> name.toLowerCase().startsWith(remaining))
                                .forEach(builder::suggest);
                    }
                    return builder.buildFuture();
                });
        CommandNode forkNewNameArg = CommandNode.argument("newName", StringArgumentType.string())
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String contextToLoad = StringArgumentType.getString(ctx, "contextToLoad");
                    String newName = StringArgumentType.getString(ctx, "newName");
                    String lowerNewName = newName.toLowerCase();

                    OBUContext sourceContext = contextManager.getContext(contextToLoad.toLowerCase());
                    if (sourceContext == null) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.parsed("sandbox", contextToLoad));
                        return 0;
                    }

                    if (contextManager.getContextNames().contains(lowerNewName)) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.parsed("sandbox", newName));
                        return 0;
                    }

                    service.createSandbox(newName);
                    for (OBUSetting setting : sourceContext.getSettings()) {
                        contextManager.updateSandboxSetting(newName, setting);
                    }

                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.forked", Placeholder.parsed("source", sourceContext.name()), Placeholder.parsed("sandbox", newName));

                    if (sender instanceof Player p) {
                        enterSandbox(p, newName, service);
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.parsed("sandbox", newName));
                        if (plugin.getConfig().getBoolean("config.show_hints", true)) {
                            plugin.getMessageManager().send(sender, "commands.obu.sandbox.hint");
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                });
        forkLoadArg.addSubcommand(forkNewNameArg);
        forkNode.addSubcommand(forkLoadArg);
        sandboxNode.addSubcommand(forkNode);

        // import
        CommandNode importNode = CommandNode.literal("import").withModule(OBUModule.class);
        CommandNode importCodeArg = CommandNode.argument("shareCode", StringArgumentType.string());
        CommandNode importNameArg = CommandNode.argument("name", StringArgumentType.string())
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String name = StringArgumentType.getString(ctx, "name");
                    String code = StringArgumentType.getString(ctx, "shareCode");
                    String lower = name.toLowerCase();

                    if (contextManager.getContextNames().contains(lower)) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.exists", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    String decodedStr;
                    try {
                        byte[] decoded = Base64.getUrlDecoder().decode(code);
                        if (decoded.length >= 2 && decoded[0] == (byte) 0x1f && decoded[1] == (byte) 0x8b) {
                            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buffer = new byte[4096];
                                int len;
                                int totalRead = 0;
                                int limit = 65536;
                                while ((len = gzip.read(buffer)) != -1) {
                                    totalRead += len;
                                    if (totalRead > limit) {
                                        throw new IOException("Decompressed payload exceeds maximum size of 64KB");
                                    }
                                    bos.write(buffer, 0, len);
                                }
                                decodedStr = bos.toString(StandardCharsets.UTF_8);
                            }
                        } else {
                            if (decoded.length > 65536) {
                                throw new IOException("Payload exceeds maximum size of 64KB.");
                            }
                            decodedStr = new String(decoded, StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        String errMsg = (e instanceof IOException && e.getMessage() != null) ? e.getMessage() : "Invalid Share Code format.";
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.import_fail", Placeholder.parsed("error", errMsg));
                        return 0;
                    }

                    service.createSandbox(name);
                    if (!decodedStr.isEmpty()) {
                        for (String part : decodedStr.split(";")) {
                            int colonIdx = part.indexOf(':');
                            if (colonIdx != -1) {
                                try {
                                    int id = Integer.parseInt(part.substring(0, colonIdx));
                                    String argsStr = part.substring(colonIdx + 1);
                                    String[] args = argsStr.isEmpty() ? new String[0] : argsStr.split(" ");
                                    OBUDefinition def = null;
                                    for (String n : OBUDefinition.getRegisteredNames()) {
                                        OBUDefinition d = OBUDefinition.get(n);
                                        if (d != null && d.id() == id) {
                                            def = d;
                                            break;
                                        }
                                    }
                                    if (def != null) {
                                        contextManager.updateSandboxSetting(name, new OBUSetting(def, args));
                                    }
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.imported", Placeholder.parsed("sandbox", name));
                    if (sender instanceof Player p) {
                        enterSandbox(p, name, service);
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.switched", Placeholder.parsed("sandbox", name));
                        if (plugin.getConfig().getBoolean("config.show_hints", true)) {
                            plugin.getMessageManager().send(sender, "commands.obu.sandbox.hint");
                        }
                    }

                    return Command.SINGLE_SUCCESS;
                });
        importCodeArg.addSubcommand(importNameArg);
        importNode.addSubcommand(importCodeArg);
        sandboxNode.addSubcommand(importNode);

        // switch
        CommandNode switchNode = CommandNode.literal("switch").withModule(OBUModule.class);
        CommandNode switchNameArg = CommandNode.argument("name", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    OBUModule module = plugin.getModule(OBUModule.class);
                    if (module != null) {
                        module.getObuService().getSandboxNames().stream()
                                .filter(name -> name.startsWith(remaining))
                                .forEach(builder::suggest);
                    }
                    return builder.buildFuture();
                })
                .executesPlayer((ctx, player) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String name = StringArgumentType.getString(ctx, "name");
                    String lower = name.toLowerCase();

                    OBUContext context = contextManager.getContext(lower);
                    if (context == null) {
                        plugin.getMessageManager().send(player, "commands.obu.sandbox.missing", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    if (!service.getSandboxNames().contains(lower)) {
                        plugin.getMessageManager().send(player, "commands.obu.sandbox.invalid", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    enterSandbox(player, lower, service);
                    plugin.getMessageManager().send(player, "commands.obu.sandbox.dropped", Placeholder.parsed("sandbox", name));
                    if (plugin.getConfig().getBoolean("config.show_hints", true)) {
                        plugin.getMessageManager().send(player, "commands.obu.sandbox.hint");
                    }
                    return Command.SINGLE_SUCCESS;
                });
        switchNode.addSubcommand(switchNameArg);
        sandboxNode.addSubcommand(switchNode);

        // exit
        CommandNode exitNode = CommandNode.literal("exit").withModule(OBUModule.class)
                .executesPlayer((ctx, player) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUService service = obuModule.getObuService();
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String sandbox = service.getPlayerActiveSandbox(player);
                    if (sandbox == null) {
                        plugin.getMessageManager().send(player, "commands.obu.sandbox.none_active");
                        return 0;
                    }

                    service.setPlayerActiveSandbox(player, null);
                    String contextName = "default";
                    OBUContext context = contextManager.getContext(contextName);
                    service.resetPlayer(player);
                    service.applyContext(player, Objects.requireNonNullElseGet(context, () -> new OBUContext("default", new ArrayList<>())));

                    if (player.getVehicle() instanceof Boat boat) {
                        service.broadcastBoatContext(boat);
                    }

                    service.getSyncManager().syncPlayer(player);

                    plugin.getMessageManager().send(player, "commands.obu.sandbox.exited");
                    return Command.SINGLE_SUCCESS;
                });
        sandboxNode.addSubcommand(exitNode);

        // view
        CommandNode viewNode = CommandNode.literal("view").withModule(OBUModule.class);
        CommandNode viewNameArg = CommandNode.argument("name", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    OBUModule module = plugin.getModule(OBUModule.class);
                    if (module != null) {
                        module.getObuService().getSandboxNames().stream()
                                .filter(name -> name.startsWith(remaining))
                                .forEach(builder::suggest);
                    }
                    return builder.buildFuture();
                })
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String name = StringArgumentType.getString(ctx, "name");
                    String lower = name.toLowerCase();

                    OBUContext context = contextManager.getContext(lower);
                    if (context == null) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.header", Placeholder.parsed("sandbox", context.name()));
                    if (context.getSettings().isEmpty()) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.empty");
                    } else {
                        for (OBUSetting setting : context.getSettings()) {
                            plugin.getMessageManager().send(sender, "commands.obu.status.line",
                                    Placeholder.parsed("name", setting.definition().name()),
                                    Placeholder.parsed("value", String.join(", ", setting.args())));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                });
        viewNode.addSubcommand(viewNameArg);
        sandboxNode.addSubcommand(viewNode);

        // export
        CommandNode exportNode = CommandNode.literal("export").withModule(OBUModule.class);
        CommandNode exportNameArg = CommandNode.argument("name", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    String remaining = builder.getRemaining().toLowerCase();
                    OBUModule module = plugin.getModule(OBUModule.class);
                    if (module != null) {
                        module.getObuService().getSandboxNames().stream()
                                .filter(name -> name.startsWith(remaining))
                                .forEach(builder::suggest);
                    }
                    return builder.buildFuture();
                })
                .executesSender((ctx, sender) -> {
                    OBUModule obuModule = plugin.getModule(OBUModule.class);
                    if (obuModule == null) return 0;
                    OBUContextManager contextManager = obuModule.getContextManager();

                    String name = StringArgumentType.getString(ctx, "name");
                    String lower = name.toLowerCase();

                    OBUContext context = contextManager.getContext(lower);
                    if (context == null) {
                        plugin.getMessageManager().send(sender, "commands.obu.sandbox.missing", Placeholder.parsed("sandbox", name));
                        return 0;
                    }

                    StringBuilder yaml = new StringBuilder();
                    yaml.append("obu:\n  contexts:\n    \"").append(escapeYaml(context.name())).append("\":\n");
                    if (context.getSettings().isEmpty()) {
                        yaml.append("      # No settings configured\n");
                    } else {
                        for (OBUSetting setting : context.getSettings()) {
                            yaml.append("      ").append(setting.definition().name()).append(": ");
                            if (setting.args().length == 1) {
                                yaml.append("\"").append(escapeYaml(setting.args()[0])).append("\"\n");
                            } else {
                                yaml.append("\n");
                                for (String arg : setting.args()) {
                                    yaml.append("        - \"").append(escapeYaml(arg)).append("\"\n");
                                }
                            }
                        }
                    }

                    StringJoiner joiner = new StringJoiner(";");
                    for (OBUSetting setting : context.getSettings()) {
                        joiner.add(setting.definition().id() + ":" + String.join(" ", setting.args()));
                    }
                    
                    String shareCode;
                    try {
                        byte[] optimalBytes = getOptimalBytes(joiner);
                        shareCode = Base64.getUrlEncoder().withoutPadding().encodeToString(optimalBytes);
                    } catch (Exception e) {
                        plugin.getLogger().severe("Failed to process sandbox share code: " + e.getMessage());
                        return 0;
                    }

                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.header_export", Placeholder.parsed("sandbox", context.name()));
                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.yaml", Placeholder.parsed("yaml", yaml.toString()));
                    plugin.getMessageManager().send(sender, "commands.obu.sandbox.code", Placeholder.parsed("code", shareCode));

                    return Command.SINGLE_SUCCESS;
                });
        exportNode.addSubcommand(exportNameArg);
        sandboxNode.addSubcommand(exportNode);

        return sandboxNode;
    }

    private static byte @NonNull [] getOptimalBytes(@NonNull StringJoiner joiner) throws IOException {
        byte[] rawBytes = joiner.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(rawBytes);
        }
        byte[] gzipBytes = baos.toByteArray();

        return (gzipBytes.length < rawBytes.length) ? gzipBytes : rawBytes;
    }

    private static void enterSandbox(Player player, String name, @NonNull OBUService service) {
        service.setPlayerActiveSandbox(player, name);
        service.resetPlayer(player);
        if (player.getVehicle() instanceof Boat boat) {
            service.broadcastBoatContext(boat);
        }
        service.getSyncManager().syncPlayer(player);
    }

    private static @NonNull String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
package dev.muggel.wake.obu.commands;

import dev.muggel.wake.Wake;
import dev.muggel.wake.core.commands.ParentCommand;
import dev.muggel.wake.core.commands.SubCommand;
import dev.muggel.wake.obu.OBUModule;
import dev.muggel.wake.obu.OBUProtocol;
import dev.muggel.wake.obu.config.OBUProfileManager;
import dev.muggel.wake.obu.networking.PacketSender;
import dev.muggel.wake.obu.service.OBUService;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class OBUParentCommand extends ParentCommand {
    private static final Set<String> UTILS = Set.of("help", "profile", "defaults");

    public OBUParentCommand(Wake plugin, OBUProfileManager profileManager, OBUService obuService, PacketSender packetSender) {
        super("wakeobu");
        this.setAliases(List.of("wobu"));
        this.setDescription("Main command for OpenBoatUtils");
        this.setUsage("/wakeobu <setting|utility> [args...]");
        this.setPermission(OBUModule.OBU_PERMISSION);
        this.setPlayerOnly(true);

        registerSubCommand(new OBUHelpSubCommand());
        registerSubCommand(new OBUDefaultsSubCommand(plugin, packetSender));
        registerSubCommand(new OBUProfileSubCommand(profileManager, obuService));

        for (String cmdName : OBUProtocol.getRegisteredNames()) {
            OBUProtocol.Definition def = OBUProtocol.get(cmdName);
            registerSubCommand(new ProtocolSubCommand(def, obuService, profileManager));
        }
    }

    @Override
    public boolean onExecute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0) {
            String subName = args[0].toLowerCase().replace("-", "");
            args[0] = subName;
        }
        return super.onExecute(sender, label, args);
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String current = args[0].toLowerCase().replace("-", "");
            
            List<String> utilitySuggestions = UTILS.stream()
                    .filter(name -> name.startsWith(current))
                    .map(name -> "-" + name + "-")
                    .sorted()
                    .toList();

            List<String> settingsSuggestions = OBUProtocol.getRegisteredNames().stream()
                    .filter(name -> name.startsWith(current))
                    .sorted()
                    .toList();

            List<String> all = new ArrayList<>(utilitySuggestions);
            all.addAll(settingsSuggestions);
            return all;
        }

        if (args.length > 1) {
            String subName = args[0].toLowerCase().replace("-", "");
            String[] newArgs = new String[args.length];
            System.arraycopy(args, 0, newArgs, 0, args.length);
            newArgs[0] = subName;
            return super.onTabComplete(sender, alias, newArgs);
        }

        return super.onTabComplete(sender, alias, args);
    }
}

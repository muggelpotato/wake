package dev.muggel.wake.core.commands;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Registry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class SmartCompleter {

    public static List<String> filter(String input, Collection<String> options) {
        String last = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(last))
                .collect(Collectors.toList());
    }

    // suggestion provider
    public static List<String> registry(String input, Registry<? extends Keyed> registry) {
        String current = input.toLowerCase();
        String prefix = "";
        String search = current;

        if (current.contains(",")) {
            int lastComma = current.lastIndexOf(",");
            prefix = current.substring(0, lastComma + 1);
            search = current.substring(lastComma + 1);
        }

        List<String> suggestions = new ArrayList<>();
        final int MAX_SUGGESTIONS = 50;

        for (Keyed item : registry) {
            if (item instanceof Material mat && !mat.isBlock()) continue;
            
            String key = item.getKey().toString();
            String justName = item.getKey().getKey();

            if (key.startsWith(search) || justName.startsWith(search)) {
                suggestions.add(prefix + key);
                if (suggestions.size() >= MAX_SUGGESTIONS) break;
            }
        }
        return suggestions;
    }

    public static List<String> BOOLEAN = List.of("true", "false");
}

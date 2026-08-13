package dev.muggel.wake.features.obu.protocol;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SettingMerge {
    private static final int MAX_UNIQUE_KEY = 255;
    private SettingMerge() {}

    public record Removal(@NonNull @Unmodifiable List<OBUSetting> kept, @NonNull @Unmodifiable List<OBUSetting> taken, @NonNull @Unmodifiable List<String> removed) {
        public static final Removal NOTHING = new Removal(List.of(), List.of(), List.of());
        public Removal {
            kept = List.copyOf(kept);
            taken = List.copyOf(taken);
            removed = List.copyOf(removed);
        }
    }

    public static @NonNull List<OBUSetting> fold(@NonNull Collection<OBUSetting> existing, @NonNull Collection<OBUSetting> incoming) {
        List<OBUSetting> folded = new ArrayList<>(existing);
        for (OBUSetting setting : incoming) {
            add(folded, setting);
        }
        return folded;
    }

    public static @NonNull Removal subtract(@NonNull Collection<OBUSetting> held, @NonNull SettingSelector selector) {
        List<String> wanted = selector.entries();
        int targetList = listArg(selector.target());
        List<OBUSetting> kept = new ArrayList<>();
        List<OBUSetting> taken = new ArrayList<>();
        Set<String> removed = new LinkedHashSet<>();
        for (OBUSetting setting : held) {
            if (!selector.matches(setting)) {
                kept.add(setting);
                continue;
            }
            if (targetList < 0) {
                taken.add(setting);
                continue;
            }
            List<String> remaining = new ArrayList<>(entries(setting, targetList));
            List<String> gone = new ArrayList<>(remaining);
            if (wanted.isEmpty()) {
                remaining.clear();
            } else {
                gone.retainAll(wanted);
                remaining.removeAll(wanted);
            }
            if (gone.isEmpty()) {
                kept.add(setting);
                continue;
            }
            taken.add(setting);
            removed.addAll(gone);
            if (!remaining.isEmpty()) {
                kept.add(withEntries(setting, targetList, remaining));
            }
        }
        return new Removal(kept, taken, List.copyOf(removed));
    }

    public static boolean takesFrom(@NonNull OBUSetting held, @NonNull SettingSelector selector) {
        return !subtract(List.of(held), selector).taken().isEmpty();
    }

    public static @NonNull @Unmodifiable Set<String> shadowedEntries(@NonNull OBUSetting held, @NonNull Collection<OBUSetting> above) {
        int list = listArg(held.definition());
        if (list < 0) {
            return Set.of();
        }
        Set<String> mine = new LinkedHashSet<>(entries(held, list));
        Set<String> gone = new LinkedHashSet<>();
        for (OBUSetting other : above) {
            if (differentFamily(held, other, list)) {
                continue;
            }
            for (String entry : entries(other, list)) {
                if (mine.contains(entry)) gone.add(entry);
            }
        }
        return Set.copyOf(gone);
    }

    public static @NonNull @Unmodifiable List<String> entriesOf(@NonNull OBUSetting setting) {
        int list = listArg(setting.definition());
        return list < 0 ? List.of() : entries(setting, list);
    }

    public static boolean coversEntries(@NonNull OBUSetting held, @NonNull Set<String> taken) {
        int list = listArg(held.definition());
        return list >= 0 && taken.containsAll(entries(held, list));
    }

    private static void add(@NonNull List<OBUSetting> folded, @NonNull OBUSetting incoming) {
        int list = listArg(incoming.definition());
        if (list < 0) {
            replace(folded, incoming);
            return;
        }
        List<String> entries = entries(incoming, list);
        boolean absorbed = false;
        for (int i = folded.size() - 1; i >= 0; i--) {
            OBUSetting held = folded.get(i);
            if (differentFamily(held, incoming, list)) {
                continue;
            }
            List<String> kept = new ArrayList<>(entries(held, list));
            if (!absorbed && sameValue(held, incoming, list)) {
                Set<String> union = new LinkedHashSet<>(kept);
                union.addAll(entries);
                if (fits(held, list, union)) {
                    folded.set(i, withEntries(held, list, union));
                    absorbed = true;
                    continue;
                }
            }
            if (!kept.removeAll(entries)) {
                continue;
            }
            if (kept.isEmpty()) {
                folded.remove(i);
            } else {
                folded.set(i, withEntries(held, list, kept));
            }
        }
        if (!absorbed) {
            spill(folded, incoming, list, entries);
        }
    }

    private static void spill(@NonNull List<OBUSetting> folded, @NonNull OBUSetting incoming, int list, @NonNull List<String> entries) {
        List<String> bucket = new ArrayList<>();
        for (String entry : entries) {
            bucket.add(entry);
            if (fits(incoming, list, bucket)) {
                continue;
            }
            bucket.removeLast();
            if (!bucket.isEmpty()) {
                folded.add(withEntries(incoming, list, bucket));
            }
            bucket = new ArrayList<>(List.of(entry));
            if (!fits(incoming, list, bucket)) {
                bucket.clear();
            }
        }
        if (!bucket.isEmpty()) {
            folded.add(withEntries(incoming, list, bucket));
        }
    }

    private static boolean fits(@NonNull OBUSetting setting, int list, @NonNull Collection<String> entries) {
        return withEntries(setting, list, entries).uniqueKey().length() <= MAX_UNIQUE_KEY;
    }

    private static void replace(@NonNull List<OBUSetting> folded, @NonNull OBUSetting incoming) {
        String key = incoming.uniqueKey();
        for (int i = 0; i < folded.size(); i++) {
            if (folded.get(i).uniqueKey().equals(key)) {
                folded.set(i, incoming);
                return;
            }
        }
        folded.add(incoming);
    }

    private static int listArg(@NonNull OBUDefinition definition) {
        List<SettingType> types = definition.types();
        int list = -1;
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).isList()) {
                if (list >= 0) return -1;
                list = i;
            }
        }
        return list;
    }

    private static boolean differentFamily(@NonNull OBUSetting held, @NonNull OBUSetting incoming, int list) {
        if (held.definition() != incoming.definition()) {
            return true;
        }
        List<SettingType> types = incoming.definition().types();
        for (int i = 0; i < types.size(); i++) {
            if (i != list && types.get(i).isIdentity() && !held.args().get(i).equals(incoming.args().get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValue(@NonNull OBUSetting held, @NonNull OBUSetting incoming, int list) {
        List<SettingType> types = incoming.definition().types();
        for (int i = 0; i < types.size(); i++) {
            if (i != list && !held.args().get(i).equals(incoming.args().get(i))) {
                return false;
            }
        }
        return true;
    }

    private static @NonNull List<String> entries(@NonNull OBUSetting setting, int list) {
        return List.of(setting.args().get(list).split(","));
    }

    private static @NonNull OBUSetting withEntries(@NonNull OBUSetting setting, int list, @NonNull Collection<String> entries) {
        List<String> args = new ArrayList<>(setting.args());
        args.set(list, String.join(",", entries));
        return new OBUSetting(setting.definition(), args);
    }
}
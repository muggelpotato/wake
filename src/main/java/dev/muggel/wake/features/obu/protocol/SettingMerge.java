package dev.muggel.wake.features.obu.protocol;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SettingMerge {
    private static final int MAX_UNIQUE_KEY = 255;
    private SettingMerge() {}

    public static @NonNull List<OBUSetting> fold(@NonNull Collection<OBUSetting> existing, @NonNull Collection<OBUSetting> incoming) {
        List<OBUSetting> folded = new ArrayList<>(existing);
        for (OBUSetting setting : incoming) {
            add(folded, setting);
        }
        return folded;
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
            if (!sameFamily(held, incoming, list)) {
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

    private static boolean sameFamily(@NonNull OBUSetting held, @NonNull OBUSetting incoming, int list) {
        if (held.definition() != incoming.definition()) {
            return false;
        }
        List<SettingType> types = incoming.definition().types();
        for (int i = 0; i < types.size(); i++) {
            if (i != list && types.get(i).isIdentity() && !held.args().get(i).equals(incoming.args().get(i))) {
                return false;
            }
        }
        return true;
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
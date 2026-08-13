package dev.muggel.wake.features.obu.protocol;

import org.jetbrains.annotations.Unmodifiable;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SettingSelector(@NonNull OBUDefinition target, @NonNull @Unmodifiable List<String> identity, @NonNull @Unmodifiable List<String> entries, @Nullable String exactKey) {
    public SettingSelector {
        identity = List.copyOf(identity);
        entries = List.copyOf(entries);
    }
    public SettingSelector(@NonNull OBUDefinition target, @NonNull List<String> identity, @NonNull List<String> entries) {
        this(target, identity, entries, null);
    }

    public static @NonNull SettingSelector of(@NonNull OBUSetting op) {
        OBUDefinition target = Objects.requireNonNull(op.definition().subtractsFrom(), "not a subtractive setting");
        return new SettingSelector(target, List.of(), SettingMerge.entriesOf(op));
    }

    public boolean matches(@NonNull OBUSetting held) {
        if (held.definition() != target) {
            return false;
        }
        if (exactKey != null) {
            return held.uniqueKey().equalsIgnoreCase(exactKey);
        }
        List<SettingType> types = target.types();
        int pinned = 0;
        for (int i = 0; i < types.size() && pinned < identity.size(); i++) {
            if (types.get(i).isList() || !types.get(i).isIdentity()) {
                continue;
            }
            if (i >= held.args().size() || !identity.get(pinned++).equals(held.args().get(i))) {
                return false;
            }
        }
        return true;
    }

    public static @NonNull SettingSelector of(@NonNull OBUDefinition target, @NonNull List<String> words) {
        List<String> identity = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        int at = 0;
        for (SettingType type : target.types()) {
            if (!type.isIdentity() || at >= words.size()) {
                continue;
            }
            if (type.isList()) {
                entries.addAll(List.of(words.get(at++).split(",")));
            } else {
                identity.add(words.get(at++));
            }
        }
        return new SettingSelector(target, identity, entries);
    }

    public static @Nullable SettingSelector ofKey(@NonNull String key) {
        int firstColon = key.indexOf(':');
        try {
            OBUDefinition target = OBUDefinition.byId(Integer.parseInt(firstColon < 0 ? key : key.substring(0, firstColon)));
            return target == null ? null : new SettingSelector(target, List.of(), List.of(), key);
        } catch (NumberFormatException notAKey) {
            return null;
        }
    }

    public static @NonNull @Unmodifiable List<String> suggestions(@NonNull OBUDefinition target, @NonNull List<String> given, @NonNull Collection<OBUSetting> held) {
        SettingSelector narrowed = of(target, given);
        List<SettingType> types = target.types();
        int skip = given.size();
        for (int i = 0; i < types.size(); i++) {
            SettingType type = types.get(i);
            if (!type.isIdentity()) {
                continue;
            }
            if (skip-- > 0) {
                continue;
            }
            return type.isList() ? entriesHeld(narrowed, held, type) : valuesHeld(narrowed, held, i);
        }
        return List.of();
    }

    private static @NonNull List<String> valuesHeld(@NonNull SettingSelector narrowed, @NonNull Collection<OBUSetting> held, int at) {
        Set<String> values = new LinkedHashSet<>();
        for (OBUSetting setting : held) {
            if (narrowed.matches(setting) && at < setting.args().size()) values.add(setting.args().get(at));
        }
        return List.copyOf(values);
    }

    private static @NonNull List<String> entriesHeld(@NonNull SettingSelector narrowed, @NonNull Collection<OBUSetting> held, @NonNull SettingType type) {
        Set<String> entries = new LinkedHashSet<>();
        for (OBUSetting setting : held) {
            if (!narrowed.matches(setting)) {
                continue;
            }
            for (String entry : SettingMerge.entriesOf(setting)) {
                if (type.accepts(entry)) entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }
}
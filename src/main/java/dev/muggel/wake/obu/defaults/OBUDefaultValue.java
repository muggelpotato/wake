package dev.muggel.wake.obu.defaults;

public record OBUDefaultValue(String name, String[] values) {
    public String getValueString() {
        return String.join(" ", values);
    }
}

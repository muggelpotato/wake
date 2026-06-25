package dev.muggel.wake.features.obu.context;

import dev.muggel.wake.features.obu.OBUDefinition;
import org.jspecify.annotations.NonNull;

public record OBUSetting(OBUDefinition definition, String[] args) {
    public OBUSetting{
        args = args == null ? new String[0] : args.clone();
    }

    @Override
    public String[] args() {
        return args.clone();
    }

    public @NonNull String getUniqueKey() {
        if (definition.canRepeat() && args.length > 0) {
            if (definition == OBUDefinition.blockslipperiness) {
                return definition.id() + ":" + (args.length > 1 ? args[1] : "");
            } else if (definition == OBUDefinition.removeblockslipperiness) {
                return definition.id() + ":" + args[0];
            } else if (definition == OBUDefinition.setblocksetting) {
                return definition.id() + ":" + args[0] + ":" + (args.length > 2 ? args[2] : "");
            } else if (definition == OBUDefinition.addcollisionfilter) {
                return definition.id() + ":" + args[0];
            }
        }
        return String.valueOf(definition.id());
    }
}

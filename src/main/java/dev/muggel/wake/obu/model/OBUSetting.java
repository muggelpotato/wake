package dev.muggel.wake.obu.model;

import dev.muggel.wake.obu.OBUProtocol;

public record OBUSetting(OBUProtocol.Definition definition, String[] args) {
    public OBUSetting{
        args = args == null ? new String[0] : args.clone();
    }

    @Override
    public String[] args() {
        return args.clone();
    }
}

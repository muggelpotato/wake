package dev.muggel.wake.obu.model;

import dev.muggel.wake.obu.OBUProtocol;

public record OBUSetting(OBUProtocol.Definition definition, String[] args) {
}

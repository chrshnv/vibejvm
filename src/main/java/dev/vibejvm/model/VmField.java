package dev.vibejvm.model;

import java.lang.reflect.Modifier;

public final class VmField {
    private final VmClass owner;
    private final String name;
    private final String descriptor;
    private final int accessFlags;
    private final int slot;

    public VmField(VmClass owner, String name, String descriptor, int accessFlags, int slot) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.accessFlags = accessFlags;
        this.slot = slot;
    }

    public VmClass owner() { return owner; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public int accessFlags() { return accessFlags; }
    public boolean isStatic() { return Modifier.isStatic(accessFlags); }
    public int slot() { return slot; }

    @Override
    public String toString() {
        return owner.name() + "." + name + ":" + descriptor;
    }
}

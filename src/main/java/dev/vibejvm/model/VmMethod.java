package dev.vibejvm.model;

import dev.vibejvm.nativeimpl.NativeHandler;

import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Modifier;

public final class VmMethod {
    private final VmClass owner;
    private final String name;
    private final String descriptor;
    private final int accessFlags;
    private final MethodModel model;
    private NativeHandler nativeHandler;

    public VmMethod(VmClass owner, String name, String descriptor, int accessFlags, MethodModel model) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.accessFlags = accessFlags;
        this.model = model;
    }

    public VmClass owner() { return owner; }
    public String name() { return name; }
    public String descriptor() { return descriptor; }
    public int accessFlags() { return accessFlags; }
    public boolean isStatic() { return Modifier.isStatic(accessFlags); }
    public MethodModel model() { return model; }
    public CodeModel code() { return model.code().orElse(null); }
    public MethodKey key() { return MethodKey.of(name, descriptor); }

    public NativeHandler nativeHandler() { return nativeHandler; }
    public void setNativeHandler(NativeHandler handler) { this.nativeHandler = handler; }

    @Override
    public String toString() {
        return owner.name() + "." + name + descriptor;
    }
}

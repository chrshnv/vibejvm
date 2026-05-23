package dev.vibejvm.model;

/**
 * String reference value in the VM heap. Its {@link #vmClass()} is the real
 * {@code java/lang/String} loaded from java.base, but its underlying storage is a
 * host {@link String} (we deliberately do not populate the real {@code byte[] value}
 * / {@code byte coder} compact-string slots — no String bytecode runs in v1).
 */
public final class VmString extends VmObject {
    private final String javaValue;

    public VmString(VmClass stringClass, String javaValue) {
        super(stringClass, new Object[stringClass.totalInstanceSlots()]);
        this.javaValue = javaValue;
    }

    public String javaValue() { return javaValue; }

    @Override
    public String toString() {
        return "VmString(\"" + javaValue + "\")";
    }
}

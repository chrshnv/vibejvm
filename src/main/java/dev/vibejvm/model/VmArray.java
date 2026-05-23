package dev.vibejvm.model;

/** Reference-array object. For v1 only the length-0 String[] passed to main is exercised. */
public final class VmArray extends VmObject {
    private final VmClass elementClass; // nullable for primitive arrays (not in v1)
    private final Object[] elements;

    public VmArray(VmClass arrayClass, VmClass elementClass, int length) {
        super(arrayClass, new Object[0]);
        this.elementClass = elementClass;
        this.elements = new Object[length];
    }

    public VmClass elementClass() { return elementClass; }
    public Object[] elements() { return elements; }
    public int length() { return elements.length; }
}

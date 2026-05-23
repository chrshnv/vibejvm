package dev.vibejvm.model;

public class VmObject {
    private final VmClass vmClass;
    private final Object[] slots;

    public VmObject(VmClass vmClass, Object[] slots) {
        this.vmClass = vmClass;
        this.slots = slots;
    }

    public VmClass vmClass() { return vmClass; }
    public Object[] slots() { return slots; }

    @Override
    public String toString() {
        return "VmObject(" + vmClass.name() + ")";
    }
}

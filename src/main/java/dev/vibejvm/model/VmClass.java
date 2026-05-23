package dev.vibejvm.model;

import java.lang.classfile.ClassModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VmClass {
    private final String name;                  // internal form, e.g. java/lang/Object
    private final String superName;             // nullable for Object
    private final VmClass superClass;           // nullable for Object
    private final int accessFlags;
    private final ClassModel model;

    // Declared on this class only:
    private final Map<MethodKey, VmMethod> declaredMethods = new LinkedHashMap<>();
    private final Map<String, VmField> declaredStaticFields = new LinkedHashMap<>();
    private final Map<String, VmField> declaredInstanceFields = new LinkedHashMap<>();

    // Storage for static field values, indexed by VmField.slot:
    private final Object[] staticSlots;
    // Total instance-field slot count across the entire hierarchy (this + supers):
    private final int totalInstanceSlots;

    public VmClass(String name, String superName, VmClass superClass,
                   int accessFlags, ClassModel model,
                   int staticSlotCount, int totalInstanceSlots) {
        this.name = name;
        this.superName = superName;
        this.superClass = superClass;
        this.accessFlags = accessFlags;
        this.model = model;
        this.staticSlots = new Object[staticSlotCount];
        this.totalInstanceSlots = totalInstanceSlots;
    }

    public String name() { return name; }
    public String superName() { return superName; }
    public VmClass superClass() { return superClass; }
    public int accessFlags() { return accessFlags; }
    public ClassModel model() { return model; }
    public Object[] staticSlots() { return staticSlots; }
    public int totalInstanceSlots() { return totalInstanceSlots; }

    public Map<MethodKey, VmMethod> declaredMethods() { return declaredMethods; }
    public Map<String, VmField> declaredStaticFields() { return declaredStaticFields; }
    public Map<String, VmField> declaredInstanceFields() { return declaredInstanceFields; }

    /**
     * Walk the class hierarchy from this class up to Object looking for a static field
     * declared with the given name. Returns null if absent.
     */
    public VmField findStaticField(String name) {
        for (VmClass c = this; c != null; c = c.superClass) {
            VmField f = c.declaredStaticFields.get(name);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * Walk the class hierarchy looking for an instance field. (Not used in v1 happy path
     * but kept for completeness.)
     */
    public VmField findInstanceField(String name) {
        for (VmClass c = this; c != null; c = c.superClass) {
            VmField f = c.declaredInstanceFields.get(name);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * Virtual-dispatch lookup: find a method by name+descriptor walking this class then
     * its superclasses. JVMS §5.4.3.3 also walks superinterfaces for default methods; v1
     * has no interfaces in its load set so we skip that.
     */
    public VmMethod findMethod(MethodKey key) {
        for (VmClass c = this; c != null; c = c.superClass) {
            VmMethod m = c.declaredMethods.get(key);
            if (m != null) return m;
        }
        return null;
    }

    /** Returns all VmClasses in the hierarchy chain, starting with this and ending at Object. */
    public List<VmClass> hierarchy() {
        List<VmClass> chain = new ArrayList<>();
        for (VmClass c = this; c != null; c = c.superClass) chain.add(c);
        return chain;
    }

    @Override
    public String toString() { return "VmClass(" + name + ")"; }
}

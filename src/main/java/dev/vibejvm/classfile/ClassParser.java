package dev.vibejvm.classfile;

import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmField;
import dev.vibejvm.model.VmMethod;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Modifier;

/**
 * Wraps {@link ClassFile} parsing and builds a {@link VmClass} shell from raw bytes.
 * The result is populated with declared fields/methods and slot indices, but not yet
 * linked into the class registry (the registry resolves the superclass and stamps
 * native handlers).
 */
public final class ClassParser {
    private static final ClassFile CLASS_FILE = ClassFile.of();

    private ClassParser() {}

    /**
     * @param bytes raw .class bytes
     * @param superClass the resolved superclass (already in the registry), or null for Object
     */
    public static VmClass parse(byte[] bytes, VmClass superClass) {
        ClassModel model = CLASS_FILE.parse(bytes);
        String name = model.thisClass().asInternalName();
        String superName = model.superclass().map(s -> s.asInternalName()).orElse(null);
        int accessFlags = model.flags().flagsMask();

        int parentInstanceSlots = superClass != null ? superClass.totalInstanceSlots() : 0;

        // First pass: count declared statics/instance fields so we can size VmClass.
        int declaredStaticCount = 0;
        int declaredInstanceCount = 0;
        for (FieldModel fm : model.fields()) {
            if (Modifier.isStatic(fm.flags().flagsMask())) declaredStaticCount++;
            else declaredInstanceCount++;
        }

        VmClass vmClass = new VmClass(
                name,
                superName,
                superClass,
                accessFlags,
                model,
                declaredStaticCount,
                parentInstanceSlots + declaredInstanceCount
        );

        // Second pass: assign slot indices and register fields.
        int nextStatic = 0;
        int nextInstance = parentInstanceSlots;
        for (FieldModel fm : model.fields()) {
            String fname = fm.fieldName().stringValue();
            String fdesc = fm.fieldType().stringValue();
            int fflags = fm.flags().flagsMask();
            if (Modifier.isStatic(fflags)) {
                VmField vf = new VmField(vmClass, fname, fdesc, fflags, nextStatic++);
                vmClass.declaredStaticFields().put(fname, vf);
            } else {
                VmField vf = new VmField(vmClass, fname, fdesc, fflags, nextInstance++);
                vmClass.declaredInstanceFields().put(fname, vf);
            }
        }

        // Register declared methods.
        for (MethodModel mm : model.methods()) {
            String mname = mm.methodName().stringValue();
            String mdesc = mm.methodType().stringValue();
            int mflags = mm.flags().flagsMask();
            VmMethod vm = new VmMethod(vmClass, mname, mdesc, mflags, mm);
            vmClass.declaredMethods().put(MethodKey.of(mname, mdesc), vm);
        }

        return vmClass;
    }
}

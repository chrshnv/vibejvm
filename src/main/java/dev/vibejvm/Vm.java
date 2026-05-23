package dev.vibejvm;

import dev.vibejvm.classfile.AppClassPath;
import dev.vibejvm.classfile.BootstrapClassLoader;
import dev.vibejvm.classfile.ClassRegistry;
import dev.vibejvm.error.LinkageException;
import dev.vibejvm.interp.Frame;
import dev.vibejvm.interp.Interpreter;
import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmArray;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmField;
import dev.vibejvm.model.VmMethod;
import dev.vibejvm.model.VmObject;
import dev.vibejvm.model.VmString;
import dev.vibejvm.nativeimpl.NativeRegistry;
import dev.vibejvm.nativeimpl.PrintStreamNatives;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class Vm {
    private final BootstrapClassLoader bootstrap;
    private final ClassRegistry classRegistry;
    private final NativeRegistry nativeRegistry;
    private final Interpreter interpreter;
    private final Map<String, VmString> stringInterns = new HashMap<>();
    private final Map<VmMethod, Integer> executedInstructionCounts = new IdentityHashMap<>();

    private VmClass objectClass;
    private VmClass stringClass;
    private VmClass systemClass;
    private VmClass printStreamClass;
    private boolean bootstrapped;

    public Vm(Path javaHome, Path appClasspath) {
        this.bootstrap = new BootstrapClassLoader(javaHome);
        this.classRegistry = new ClassRegistry(bootstrap, new AppClassPath(appClasspath));
        this.nativeRegistry = new NativeRegistry();
        this.interpreter = new Interpreter(this);
    }

    public ClassRegistry classRegistry() { return classRegistry; }
    public NativeRegistry nativeRegistry() { return nativeRegistry; }
    public Interpreter interpreter() { return interpreter; }
    public VmClass stringClass() { return stringClass; }
    public VmClass systemClass() { return systemClass; }
    public VmClass printStreamClass() { return printStreamClass; }

    /** Returns the unique VmString for a given Java string (per JVMS ldc-string semantics). */
    public VmString internString(String value) {
        return stringInterns.computeIfAbsent(value, v -> new VmString(stringClass, v));
    }

    /** Test hook: how many bytecode instructions ran in a given method this session. */
    public void recordExecutedInstructions(VmMethod method, int count) {
        executedInstructionCounts.merge(method, count, Integer::sum);
    }
    public int executedInstructionCount(VmMethod method) {
        return executedInstructionCounts.getOrDefault(method, 0);
    }

    /**
     * Phase 1: eagerly resolve the core classes, register natives, stamp handlers, and
     * fabricate the synthetic {@code System.out} reference. {@code <clinit>} runs for
     * nothing.
     */
    public void bootstrap() {
        if (bootstrapped) return;

        // Resolve in an order that ensures supers come up first naturally.
        objectClass      = classRegistry.resolve("java/lang/Object");
        stringClass      = classRegistry.resolve("java/lang/String");
        systemClass      = classRegistry.resolve("java/lang/System");
        printStreamClass = classRegistry.resolve("java/io/PrintStream"); // pulls FilterOutputStream + OutputStream

        // Register and stamp natives. Stamping touches every loaded class so any future
        // resolve also gets stamped — but to keep it explicit we re-stamp now for what's
        // loaded so far.
        PrintStreamNatives.register(nativeRegistry);
        for (String name : classRegistry.loadedNames()) {
            VmClass c = classRegistry.resolve(name);
            nativeRegistry.stamp(c);
        }

        // Fabricate a synthetic PrintStream and pin it as System.out.
        VmObject syntheticOut = new VmObject(
                printStreamClass,
                new Object[printStreamClass.totalInstanceSlots()]
        );
        VmField outField = systemClass.findStaticField("out");
        if (outField == null) {
            throw new LinkageException("could not locate java.lang.System.out");
        }
        systemClass.staticSlots()[outField.slot()] = syntheticOut;

        bootstrapped = true;
    }

    /** Read the value of a static field. Convenience for tests/bootstrap assertions. */
    public Object getStaticField(VmClass owner, String name) {
        VmField f = owner.findStaticField(name);
        if (f == null) throw new LinkageException("no static field " + owner.name() + "." + name);
        return f.owner().staticSlots()[f.slot()];
    }

    /** Entry point: bootstrap (idempotent) then invoke main on the named class. */
    public void run(String mainClassInternalOrDotted, String[] args) {
        bootstrap();

        String internal = mainClassInternalOrDotted.replace('.', '/');
        // Also resolve app class so a stamping pass runs on it (in case it ever needs it).
        VmClass mainClass = classRegistry.resolve(internal);
        nativeRegistry.stamp(mainClass);

        VmMethod main = mainClass.findMethod(MethodKey.of("main", "([Ljava/lang/String;)V"));
        if (main == null) {
            throw new LinkageException("no public static void main(String[]) in " + internal);
        }

        VmArray argv = new VmArray(/*arrayClass*/ objectClass, stringClass, args.length);
        for (int i = 0; i < args.length; i++) {
            argv.elements()[i] = internString(args[i]);
        }

        Frame frame = new Frame(main);
        frame.locals()[0] = argv;
        interpreter.execute(frame);
    }
}

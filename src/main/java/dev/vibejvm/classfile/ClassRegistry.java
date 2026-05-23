package dev.vibejvm.classfile;

import dev.vibejvm.error.LinkageException;
import dev.vibejvm.model.VmClass;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads + links classes on demand. Looks up bytes first via {@link BootstrapClassLoader}
 * (java.base.jmod), then via the app classpath. Recursively resolves the superclass
 * chain. {@code <clinit>} is intentionally never invoked.
 *
 * <p>TODO v2: real class initialization (run {@code <clinit>} per JVMS §5.5). The
 * deliberate skip is what allows v1 to bypass the massive {@code java.lang.System}
 * bootstrap.
 */
public final class ClassRegistry {
    private final BootstrapClassLoader bootstrap;
    private final AppClassPath appClasspath;
    private final Map<String, VmClass> loaded = new LinkedHashMap<>();

    public ClassRegistry(BootstrapClassLoader bootstrap, AppClassPath appClasspath) {
        this.bootstrap = bootstrap;
        this.appClasspath = appClasspath;
    }

    /** Loaded class snapshot — used by tests to assert the exact transitive load set. */
    public Set<String> loadedNames() { return loaded.keySet(); }

    public VmClass resolve(String internalName) {
        VmClass existing = loaded.get(internalName);
        if (existing != null) return existing;

        byte[] bytes = readBytes(internalName);
        if (bytes == null) {
            throw new LinkageException("class not found: " + internalName);
        }

        // Peek at the superclass without fully parsing into VmClass — we need the parent
        // resolved first so VmClass can compute its slot layout.
        java.lang.classfile.ClassModel peek = java.lang.classfile.ClassFile.of().parse(bytes);
        String superName = peek.superclass().map(s -> s.asInternalName()).orElse(null);
        VmClass superClass = (superName != null) ? resolve(superName) : null;

        VmClass parsed = ClassParser.parse(bytes, superClass);
        loaded.put(internalName, parsed);
        return parsed;
    }

    private byte[] readBytes(String internalName) {
        // Bootstrap takes precedence for java/* names; app classpath for everything else.
        // This is a simplification — a real loader has the parent-delegation model — but
        // it suffices for v1.
        if (internalName.startsWith("java/") || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/") || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")) {
            return bootstrap.read(internalName);
        }
        byte[] app = appClasspath.read(internalName);
        if (app != null) return app;
        return bootstrap.read(internalName);
    }
}

package dev.vibejvm.nativeimpl;

import dev.vibejvm.error.NativeMissingException;
import dev.vibejvm.model.VmString;

/** The one Java-side intercept that makes Hello World print. */
public final class PrintStreamNatives {
    private PrintStreamNatives() {}

    public static void register(NativeRegistry registry) {
        registry.register("java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                (receiver, args, vm) -> {
                    Object arg = args[0];
                    if (arg == null) {
                        // matches PrintStream.println((String)null) which prints "null"
                        System.out.println("null");
                        return null;
                    }
                    if (!(arg instanceof VmString vs)) {
                        throw new NativeMissingException(
                                "PrintStream.println(String) expected VmString arg, got "
                                        + arg.getClass().getName());
                    }
                    System.out.println(vs.javaValue());
                    return null;
                });

        // Defensive no-ops: these should never fire in v1 (we don't init Object and we
        // don't call any constructor), but registering them now means any accidental
        // call gets a clean no-op rather than a confusing crash.
        registry.register("java/lang/Object", "<init>", "()V",
                (receiver, args, vm) -> null);
        registry.register("java/lang/Object", "registerNatives", "()V",
                (receiver, args, vm) -> null);
    }
}

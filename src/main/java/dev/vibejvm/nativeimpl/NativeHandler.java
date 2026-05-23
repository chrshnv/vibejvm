package dev.vibejvm.nativeimpl;

import dev.vibejvm.Vm;

/** A Java-side handler for an intercepted JVM method invocation. */
@FunctionalInterface
public interface NativeHandler {
    /**
     * @param receiver the receiver reference for virtual calls; null for static calls
     * @param args the method arguments (length matches the descriptor's parameter count)
     * @param vm the running VM (for class lookups, interning, etc.)
     * @return the method's return value, or null for void
     */
    Object invoke(Object receiver, Object[] args, Vm vm);
}

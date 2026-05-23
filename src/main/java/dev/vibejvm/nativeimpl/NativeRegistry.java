package dev.vibejvm.nativeimpl;

import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmMethod;

import java.util.HashMap;
import java.util.Map;

/** Registry of {@code (owner, name, descriptor) -> NativeHandler}. */
public final class NativeRegistry {
    private record Key(String owner, String name, String descriptor) {}

    private final Map<Key, NativeHandler> handlers = new HashMap<>();

    public void register(String owner, String name, String descriptor, NativeHandler handler) {
        handlers.put(new Key(owner, name, descriptor), handler);
    }

    public NativeHandler lookup(String owner, String name, String descriptor) {
        return handlers.get(new Key(owner, name, descriptor));
    }

    /** After a class is linked, stamp {@code nativeHandler} onto any method we own. */
    public void stamp(VmClass vmClass) {
        for (Map.Entry<MethodKey, VmMethod> e : vmClass.declaredMethods().entrySet()) {
            VmMethod m = e.getValue();
            NativeHandler h = lookup(vmClass.name(), m.name(), m.descriptor());
            if (h != null) {
                m.setNativeHandler(h);
            }
        }
    }
}

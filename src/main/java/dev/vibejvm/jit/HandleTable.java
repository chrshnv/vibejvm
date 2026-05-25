package dev.vibejvm.jit;

import java.util.ArrayList;
import java.util.List;

/**
 * GC-safe bridge between native code — which only ever sees opaque {@code int} handles — and
 * VM heap objects. Handle {@code 0} is reserved for {@code null} / a void result. Handles are
 * never reclaimed in v1: programs are tiny, so the monotonic table is an accepted leak.
 */
final class HandleTable {
    private final List<Object> objects = new ArrayList<>();

    HandleTable() {
        objects.add(null); // index 0 == null / void
    }

    int box(Object o) {
        if (o == null) return 0;
        objects.add(o);
        return objects.size() - 1;
    }

    Object unbox(int handle) {
        return objects.get(handle);
    }
}

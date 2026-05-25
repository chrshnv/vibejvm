package dev.vibejvm.jit;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * A guest method compiled to native code. {@code entry} is a downcall handle of shape
 * {@code (MemorySegment frameBase) -> int}: arguments and locals arrive pre-written in the
 * off-heap frame buffer, and the returned {@code int} is the result handle (0 for void).
 * {@code compiledInstructionCount} is how many guest bytecode instructions the JIT lowered.
 */
record CompiledMethod(MemorySegment code, MethodHandle entry,
                      int maxLocals, int maxStack, int compiledInstructionCount) {

    /** Total 8-byte slots in a frame buffer: locals first, then the operand stack. */
    int frameSlots() {
        return maxLocals + maxStack;
    }
}

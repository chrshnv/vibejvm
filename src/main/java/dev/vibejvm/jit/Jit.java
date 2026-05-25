package dev.vibejvm.jit;

import dev.vibejvm.Vm;
import dev.vibejvm.error.LinkageException;
import dev.vibejvm.error.UnsupportedOpcodeException;
import dev.vibejvm.model.DescriptorParser;
import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmArray;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmField;
import dev.vibejvm.model.VmMethod;
import dev.vibejvm.model.VmObject;
import dev.vibejvm.nativeimpl.NativeHandler;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static dev.vibejvm.jit.Aarch64Assembler.X0;
import static dev.vibejvm.jit.Aarch64Assembler.X1;
import static dev.vibejvm.jit.Aarch64Assembler.X2;
import static dev.vibejvm.jit.Aarch64Assembler.X9;
import static dev.vibejvm.jit.Aarch64Assembler.X19;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Template JIT: lowers each guest method to native AArch64 code on first call and runs it from
 * executable memory via the Foreign Function &amp; Memory API. There is no interpreter — this is
 * the whole execution engine.
 *
 * <p>The native code keeps the operand stack and locals in an off-heap frame buffer (one 8-byte
 * slot each) addressed through x19. Stack heights are statically known per bytecode, so every
 * value lives at a fixed, compile-time slot offset — there is no runtime stack pointer. Heap
 * operations (static reads, string interning, call dispatch) are done by runtime helpers reached
 * via FFM upcall stubs; references cross the native boundary only as {@code int} handles into a
 * {@link HandleTable}, never as raw pointers.
 *
 * <p>{@code Vm} owns the {@code Jit} and the {@code Jit} holds a back-reference for class
 * resolution, string interning and native dispatch — the same circular wiring the interpreter had.
 */
public final class Jit {
    private final Vm vm;
    private final boolean trace;

    private final Linker linker = Linker.nativeLinker();
    private final Arena arena = Arena.ofShared(); // lives for the VM's lifetime: code + upcall stubs
    private final JitMemory jitMemory = new JitMemory();
    private final HandleTable handles = new HandleTable();

    // Compiled-code cache and the two counters the anti-cheat test reads.
    private final Map<VmMethod, CompiledMethod> cache = new IdentityHashMap<>();
    private final Map<VmMethod, Integer> compileCounts = new IdentityHashMap<>();
    private final Map<VmMethod, Integer> nativeCounts = new IdentityHashMap<>();

    // Compile-time side tables: native code carries small int ids, helpers resolve them at runtime.
    private record FieldSite(String owner, String name) {}
    private record CallSite(Opcode op, String owner, String name, String desc) {}
    private final List<FieldSite> fieldSites = new ArrayList<>();
    private final List<String> stringConsts = new ArrayList<>();
    private final List<CallSite> callSites = new ArrayList<>();

    // Addresses of the runtime-helper upcall stubs, baked into emitted code as movz/movk immediates.
    private final long getStaticStub;
    private final long ldcStub;
    private final long invokeStub;

    // (MemorySegment frameBase) -> int handle. Uniform for every compiled method.
    private static final FunctionDescriptor THUNK = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    public Jit(Vm vm) {
        this.vm = vm;
        this.trace = Boolean.getBoolean("vibejvm.trace");
        try {
            MethodHandles.Lookup lk = MethodHandles.lookup();
            MethodHandle gs = lk.findVirtual(Jit.class, "getStatic",
                    MethodType.methodType(int.class, int.class)).bindTo(this);
            MethodHandle ldc = lk.findVirtual(Jit.class, "ldcString",
                    MethodType.methodType(int.class, int.class)).bindTo(this);
            MethodHandle inv = lk.findVirtual(Jit.class, "invoke",
                    MethodType.methodType(int.class, int.class, MemorySegment.class, int.class)).bindTo(this);
            this.getStaticStub = linker.upcallStub(gs, FunctionDescriptor.of(JAVA_INT, JAVA_INT), arena).address();
            this.ldcStub = linker.upcallStub(ldc, FunctionDescriptor.of(JAVA_INT, JAVA_INT), arena).address();
            this.invokeStub = linker.upcallStub(inv,
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), arena).address();
        } catch (ReflectiveOperationException e) {
            throw new LinkageException("could not bind JIT runtime helpers", e);
        }
    }

    public int compileCount(VmMethod m) { return compileCounts.getOrDefault(m, 0); }
    public int nativeInvocationCount(VmMethod m) { return nativeCounts.getOrDefault(m, 0); }

    /** Guest bytecode instructions lowered for {@code m}, or -1 if it was never compiled. */
    public int compiledInstructionCount(VmMethod m) {
        CompiledMethod cm = cache.get(m);
        return cm == null ? -1 : cm.compiledInstructionCount();
    }

    /** Entry point: compile {@code main}, lay {@code argv} into local 0, and run the native code. */
    public void run(VmMethod main, VmArray argv) {
        CompiledMethod cm = compile(main);
        try (Arena frameArena = Arena.ofConfined()) {
            MemorySegment frame = frameArena.allocate((long) cm.frameSlots() * 8);
            frame.setAtIndex(JAVA_LONG, 0, handles.box(argv)); // String[] args -> local 0
            cm.entry().invoke(frame);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new LinkageException("JIT execution of " + main + " failed", t);
        }
    }

    // --- compilation -----------------------------------------------------------------------------

    public CompiledMethod compile(VmMethod method) {
        CompiledMethod cached = cache.get(method);
        if (cached != null) return cached;

        if (method.code() == null) {
            throw new IllegalStateException("no code attribute for " + method);
        }
        CodeAttribute attr = (CodeAttribute) method.code();
        int maxLocals = attr.maxLocals();
        int maxStack = attr.maxStack();

        Aarch64Assembler a = new Aarch64Assembler();
        a.prologue();
        a.movReg(X19, X0); // x19 = frame base pointer for this method's lifetime

        if (trace) {
            System.err.println("[jit] compiling " + method
                    + " (maxLocals=" + maxLocals + ", maxStack=" + maxStack + ")");
        }

        int depth = 0;          // abstract operand-stack height, in value slots
        int insnCount = 0;
        for (CodeElement element : method.code()) {
            if (!(element instanceof Instruction instr)) {
                continue; // pseudo-elements: LineNumber, StackMapTable, ...
            }
            insnCount++;
            int before = a.instructionCount();
            depth = emit(a, instr, method, maxLocals, depth);
            if (trace) traceTemplate(a, instr, before);
        }

        byte[] code = a.toBytes();
        MemorySegment seg = jitMemory.commit(code);
        MethodHandle entry = linker.downcallHandle(seg, THUNK);
        CompiledMethod cm = new CompiledMethod(seg, entry, maxLocals, maxStack, insnCount);
        cache.put(method, cm);
        compileCounts.merge(method, 1, Integer::sum);
        if (trace) {
            System.err.printf("[jit] committed %s -> 0x%x (%d guest insns, %d native words)%n",
                    method, seg.address(), insnCount, code.length / 4);
        }
        return cm;
    }

    /** Emit the template for one instruction; returns the new abstract stack depth. */
    private int emit(Aarch64Assembler a, Instruction instr, VmMethod method, int maxLocals, int depth) {
        switch (instr) {
            case FieldInstruction fi -> {
                if (fi.opcode() != Opcode.GETSTATIC) {
                    throw new UnsupportedOpcodeException("field op " + fi.opcode() + " not supported");
                }
                FieldRefEntry ref = fi.field();
                int id = intern(fieldSites, new FieldSite(ref.owner().asInternalName(), ref.name().stringValue()));
                callHelper1(a, id, getStaticStub);            // x0 = getStatic(id)
                pushResult(a, maxLocals, depth);
                return depth + 1;
            }
            case ConstantInstruction ci -> {
                if (ci instanceof ConstantInstruction.LoadConstantInstruction lci
                        && lci.constantEntry() instanceof StringEntry se) {
                    int id = intern(stringConsts, se.stringValue());
                    callHelper1(a, id, ldcStub);              // x0 = ldcString(id)
                    pushResult(a, maxLocals, depth);
                    return depth + 1;
                }
                throw new UnsupportedOpcodeException("only string ldc is supported, got " + ci.opcode());
            }
            case InvokeInstruction ii -> {
                Opcode op = ii.opcode();
                if (op != Opcode.INVOKEVIRTUAL && op != Opcode.INVOKESPECIAL && op != Opcode.INVOKESTATIC) {
                    throw new UnsupportedOpcodeException("invoke op " + op + " not supported");
                }
                MemberRefEntry ref = ii.method();
                String owner = ((ClassEntry) ref.owner()).asInternalName();
                String name = ref.name().stringValue();
                String desc = ((NameAndTypeEntry) ref.nameAndType()).type().stringValue();
                int consumed = DescriptorParser.parameterCount(desc) + (op == Opcode.INVOKESTATIC ? 0 : 1);
                int argBaseSlot = maxLocals + depth - consumed;
                int id = callSites.size();
                callSites.add(new CallSite(op, owner, name, desc));
                a.movImm16(X0, id);                            // w0 = methodId
                a.movReg(X1, X19);                             // x1 = frame base
                a.movImm16(X2, argBaseSlot);                   // w2 = arg base slot index
                a.movImm64(X9, invokeStub);
                a.blr(X9);                                     // x0 = invoke(methodId, frame, argBaseSlot)
                int newDepth = depth - consumed;
                if (!DescriptorParser.returnsVoid(desc)) {
                    pushResult(a, maxLocals, newDepth);
                    return newDepth + 1;
                }
                return newDepth;
            }
            case ReturnInstruction ri -> {
                if (ri.opcode() == Opcode.RETURN) {
                    a.movImm16(X0, 0);                          // void -> handle 0
                } else {
                    a.ldrSlot(X0, X19, maxLocals + depth - 1);  // {a,i,l,f,d}return -> top-of-stack handle
                }
                a.epilogue();
                return depth;
            }
            case LoadInstruction li -> {
                a.ldrSlot(X0, X19, li.slot());                  // local -> operand top
                a.strSlot(X0, X19, maxLocals + depth);
                return depth + 1;
            }
            case StoreInstruction si -> {
                a.ldrSlot(X0, X19, maxLocals + depth - 1);      // operand top -> local
                a.strSlot(X0, X19, si.slot());
                return depth - 1;
            }
            case StackInstruction si -> {
                switch (si.opcode()) {
                    case POP -> { return depth - 1; }
                    case DUP -> {
                        a.ldrSlot(X0, X19, maxLocals + depth - 1);
                        a.strSlot(X0, X19, maxLocals + depth);
                        return depth + 1;
                    }
                    default -> throw new UnsupportedOpcodeException("stack op " + si.opcode() + " not supported");
                }
            }
            case NopInstruction nop -> { return depth; }
            default -> throw new UnsupportedOpcodeException(
                    "unsupported opcode " + instr.opcode() + " in " + method);
        }
    }

    /** w0 = stub(id); the single-int-arg helper-call template (getstatic, ldc). */
    private void callHelper1(Aarch64Assembler a, int id, long stub) {
        a.movImm16(X0, id);
        a.movImm64(X9, stub);
        a.blr(X9);
    }

    /** Store the helper result handle (in w0) onto the operand stack at {@code depth}. */
    private void pushResult(Aarch64Assembler a, int maxLocals, int depth) {
        a.zeroExtendW(X0, X0);                 // guarantee bits 63:32 are clear before a 64-bit store
        a.strSlot(X0, X19, maxLocals + depth);
    }

    private static <T> int intern(List<T> table, T value) {
        int existing = table.indexOf(value);
        if (existing >= 0) return existing;
        table.add(value);
        return table.size() - 1;
    }

    private void traceTemplate(Aarch64Assembler a, Instruction instr, int before) {
        int[] words = a.words();
        StringBuilder sb = new StringBuilder();
        for (int i = before; i < words.length; i++) sb.append(String.format("%08x ", words[i]));
        System.err.println("[jit]   " + instr.opcode() + " -> " + sb.toString().trim());
    }

    // --- runtime helpers (invoked from native code through FFM upcall stubs) ---------------------

    @SuppressWarnings("unused") // reached only via the upcall stub bound in the constructor
    private int getStatic(int fieldId) {
        FieldSite fs = fieldSites.get(fieldId);
        VmClass owner = vm.classRegistry().resolve(fs.owner());
        VmField field = owner.findStaticField(fs.name());
        if (field == null) {
            throw new LinkageException("static field not found: " + fs.owner() + "." + fs.name());
        }
        return handles.box(field.owner().staticSlots()[field.slot()]);
    }

    @SuppressWarnings("unused")
    private int ldcString(int constId) {
        return handles.box(vm.internString(stringConsts.get(constId)));
    }

    @SuppressWarnings("unused")
    private int invoke(int methodId, MemorySegment frameBase, int argBaseSlot) {
        CallSite cs = callSites.get(methodId);
        int paramCount = DescriptorParser.parameterCount(cs.desc());
        boolean isStatic = cs.op() == Opcode.INVOKESTATIC;
        int consumed = paramCount + (isStatic ? 0 : 1);

        // The upcall hands us a zero-length view; widen it to reach the operand slots we read.
        MemorySegment frame = frameBase.reinterpret((long) (argBaseSlot + consumed + 1) * 8);
        int slot = argBaseSlot;
        Object receiver = isStatic ? null : handles.unbox((int) frame.getAtIndex(JAVA_LONG, slot++));
        Object[] args = new Object[paramCount];
        for (int i = 0; i < paramCount; i++) {
            args[i] = handles.unbox((int) frame.getAtIndex(JAVA_LONG, slot++));
        }

        VmClass ownerClass = vm.classRegistry().resolve(cs.owner());
        VmMethod target;
        if (isStatic) {
            target = ownerClass.findMethod(MethodKey.of(cs.name(), cs.desc()));
        } else if (cs.op() == Opcode.INVOKEVIRTUAL && receiver instanceof VmObject vo) {
            target = vo.vmClass().findMethod(MethodKey.of(cs.name(), cs.desc())); // virtual dispatch
        } else {
            target = ownerClass.findMethod(MethodKey.of(cs.name(), cs.desc()));
        }
        if (target == null) {
            throw new LinkageException("method not found: " + cs.owner() + "." + cs.name() + cs.desc());
        }

        Object ret;
        NativeHandler handler = target.nativeHandler();
        if (handler != null) {
            ret = handler.invoke(receiver, args, vm);
            nativeCounts.merge(target, 1, Integer::sum);
        } else {
            ret = invokeCompiled(target, receiver, args); // compile-on-first-call, then run native
        }
        return DescriptorParser.returnsVoid(cs.desc()) ? 0 : handles.box(ret);
    }

    private Object invokeCompiled(VmMethod target, Object receiver, Object[] args) {
        CompiledMethod cm = compile(target);
        try (Arena frameArena = Arena.ofConfined()) {
            MemorySegment frame = frameArena.allocate((long) cm.frameSlots() * 8);
            int slot = 0;
            if (!target.isStatic()) frame.setAtIndex(JAVA_LONG, slot++, handles.box(receiver));
            for (Object arg : args) frame.setAtIndex(JAVA_LONG, slot++, handles.box(arg));
            int retHandle = (int) cm.entry().invoke(frame);
            return DescriptorParser.returnsVoid(target.descriptor()) ? null : handles.unbox(retHandle);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new LinkageException("JIT call into " + target + " failed", t);
        }
    }
}

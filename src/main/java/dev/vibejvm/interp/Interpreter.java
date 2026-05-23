package dev.vibejvm.interp;

import dev.vibejvm.Vm;
import dev.vibejvm.error.LinkageException;
import dev.vibejvm.error.UnsupportedOpcodeException;
import dev.vibejvm.model.DescriptorParser;
import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmField;
import dev.vibejvm.model.VmMethod;
import dev.vibejvm.model.VmObject;
import dev.vibejvm.nativeimpl.NativeHandler;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;

/**
 * Bytecode interpreter. Walks {@link java.lang.classfile.CodeModel#elementStream()},
 * dispatches on instruction subtype. Only the opcodes required for HelloWorld's
 * happy path are implemented; anything else throws {@link UnsupportedOpcodeException}.
 */
public final class Interpreter {
    private final Vm vm;
    private final boolean trace;

    public Interpreter(Vm vm) {
        this.vm = vm;
        this.trace = Boolean.getBoolean("vibejvm.trace");
    }

    /** Execute a method frame. Returns the return value (or null for void). */
    public Object execute(Frame frame) {
        VmMethod method = frame.method();
        int instructionsExecuted = 0;
        int pc = 0;
        for (CodeElement element : method.code()) {
            if (!(element instanceof Instruction instr)) {
                continue; // pseudo-elements: LineNumber, StackMapTable, ...
            }
            if (trace) {
                System.err.println("[trace] " + method + " pc=" + pc + " " + instr);
            }
            pc += instr.sizeInBytes();
            instructionsExecuted++;
            Object result = dispatch(frame, instr);
            if (result == RETURNED) {
                if (trace) {
                    System.err.println("[trace] " + method + " returned after "
                            + instructionsExecuted + " instructions");
                }
                vm.recordExecutedInstructions(method, instructionsExecuted);
                return frame.sp() == 0 ? null : frame.pop();
            }
        }
        // Fell off the end with no return — should never happen for valid bytecode.
        vm.recordExecutedInstructions(method, instructionsExecuted);
        return null;
    }

    /** Sentinel returned by handlers that performed a method return. */
    private static final Object RETURNED = new Object();

    private Object dispatch(Frame frame, Instruction instr) {
        switch (instr) {
            case FieldInstruction fi -> doField(frame, fi);
            case ConstantInstruction ci -> doConstant(frame, ci);
            case InvokeInstruction ii -> doInvoke(frame, ii);
            case ReturnInstruction ri -> { return RETURNED; }
            case LoadInstruction li -> doLoad(frame, li);
            case StoreInstruction si -> doStore(frame, si);
            case StackInstruction si -> doStack(frame, si);
            case NopInstruction nop -> { /* no-op */ }
            case OperatorInstruction op -> throw unsupported(instr, frame);
            default -> throw unsupported(instr, frame);
        }
        return null;
    }

    private UnsupportedOpcodeException unsupported(Instruction instr, Frame frame) {
        return new UnsupportedOpcodeException(
                "unsupported opcode " + instr.opcode() + " in " + frame.method());
    }

    // --- getstatic / putstatic (only getstatic in v1) -------------------------------
    private void doField(Frame frame, FieldInstruction fi) {
        if (fi.opcode() != Opcode.GETSTATIC) {
            throw new UnsupportedOpcodeException(
                    "field op " + fi.opcode() + " not supported in v1");
        }
        FieldRefEntry ref = fi.field();
        VmClass owner = vm.classRegistry().resolve(ref.owner().asInternalName());
        VmField field = owner.findStaticField(ref.name().stringValue());
        if (field == null) {
            throw new LinkageException("static field not found: "
                    + ref.owner().asInternalName() + "." + ref.name().stringValue());
        }
        Object value = field.owner().staticSlots()[field.slot()];
        frame.push(value);
    }

    // --- ldc / ldc_w / ldc2_w ---------------------------------------------------------
    private void doConstant(Frame frame, ConstantInstruction ci) {
        // Handle string constants (the only kind HelloWorld uses).
        if (ci instanceof ConstantInstruction.LoadConstantInstruction lci) {
            var entry = lci.constantEntry();
            switch (entry) {
                case StringEntry se -> frame.push(vm.internString(se.stringValue()));
                default -> {
                    // Fall back to constantValue() for primitives (Integer/Long/Float/Double).
                    Object v = lci.constantValue();
                    if (v == null) {
                        throw unsupported(ci, frame);
                    }
                    frame.push(v);
                }
            }
            return;
        }
        // IntrinsicConstantInstruction: aconst_null, iconst_*, lconst_*, fconst_*, dconst_*
        // ArgumentConstantInstruction: bipush, sipush
        Object v = ci.constantValue();
        if (v == null && ci.opcode() == Opcode.ACONST_NULL) {
            frame.push(null);
            return;
        }
        if (v == null) {
            throw unsupported(ci, frame);
        }
        frame.push(v);
    }

    // --- invokevirtual / invokespecial / invokestatic ---------------------------------
    private void doInvoke(Frame frame, InvokeInstruction ii) {
        Opcode op = ii.opcode();
        if (op != Opcode.INVOKEVIRTUAL && op != Opcode.INVOKESPECIAL && op != Opcode.INVOKESTATIC) {
            throw new UnsupportedOpcodeException(
                    "invoke op " + op + " not supported in v1");
        }
        MemberRefEntry ref = ii.method();
        String ownerName = ((ClassEntry) ref.owner()).asInternalName();
        String name = ref.name().stringValue();
        String desc = ((java.lang.classfile.constantpool.NameAndTypeEntry) ref.nameAndType())
                .type().stringValue();

        VmClass ownerClass = vm.classRegistry().resolve(ownerName);

        // Pop arguments (right to left).
        int paramCount = DescriptorParser.parameterCount(desc);
        Object[] args = new Object[paramCount];
        for (int i = paramCount - 1; i >= 0; i--) {
            args[i] = frame.pop();
        }

        Object receiver = null;
        VmMethod target;
        if (op == Opcode.INVOKESTATIC) {
            target = ownerClass.findMethod(MethodKey.of(name, desc));
        } else {
            receiver = frame.pop();
            if (op == Opcode.INVOKEVIRTUAL && receiver instanceof VmObject vo) {
                // Virtual dispatch on runtime class.
                target = vo.vmClass().findMethod(MethodKey.of(name, desc));
            } else {
                target = ownerClass.findMethod(MethodKey.of(name, desc));
            }
        }
        if (target == null) {
            throw new LinkageException("method not found: " + ownerName + "." + name + desc);
        }

        Object ret;
        NativeHandler handler = target.nativeHandler();
        if (handler != null) {
            ret = handler.invoke(receiver, args, vm);
        } else {
            Frame callee = new Frame(target);
            int slot = 0;
            if (!target.isStatic()) {
                callee.locals()[slot++] = receiver;
            }
            for (Object a : args) {
                callee.locals()[slot++] = a;
            }
            ret = execute(callee);
        }

        if (!DescriptorParser.returnsVoid(desc)) {
            frame.push(ret);
        }
    }

    // --- aload_* / iload_* ------------------------------------------------------------
    private void doLoad(Frame frame, LoadInstruction li) {
        frame.push(frame.locals()[li.slot()]);
    }

    // --- astore_* / istore_* ----------------------------------------------------------
    private void doStore(Frame frame, StoreInstruction si) {
        frame.locals()[si.slot()] = frame.pop();
    }

    // --- pop / dup --------------------------------------------------------------------
    private void doStack(Frame frame, StackInstruction si) {
        switch (si.opcode()) {
            case POP -> frame.pop();
            case DUP -> frame.push(frame.peek());
            default -> throw new UnsupportedOpcodeException(
                    "stack op " + si.opcode() + " not supported in v1");
        }
    }
}

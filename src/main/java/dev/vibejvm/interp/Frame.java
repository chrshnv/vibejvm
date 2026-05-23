package dev.vibejvm.interp;

import dev.vibejvm.model.VmMethod;

import java.lang.classfile.CodeModel;
import java.lang.classfile.attribute.CodeAttribute;

public final class Frame {
    private final VmMethod method;
    private final Object[] locals;
    private final Object[] stack;
    private int sp;

    public Frame(VmMethod method) {
        CodeModel code = method.code();
        if (code == null) {
            throw new IllegalStateException("no code attribute for " + method);
        }
        CodeAttribute attr = (CodeAttribute) code;
        this.method = method;
        this.locals = new Object[attr.maxLocals()];
        this.stack = new Object[attr.maxStack()];
        this.sp = 0;
    }

    public VmMethod method() { return method; }
    public Object[] locals() { return locals; }

    public void push(Object value) {
        stack[sp++] = value;
    }

    public Object pop() {
        return stack[--sp];
    }

    public Object peek() {
        return stack[sp - 1];
    }

    public int sp() { return sp; }
}

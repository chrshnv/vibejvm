package dev.vibejvm.model;

import java.lang.constant.MethodTypeDesc;

/** Tiny helper around {@link MethodTypeDesc} for the few facts the interpreter needs. */
public final class DescriptorParser {
    private DescriptorParser() {}

    public static int parameterCount(String descriptor) {
        return MethodTypeDesc.ofDescriptor(descriptor).parameterCount();
    }

    public static boolean returnsVoid(String descriptor) {
        return MethodTypeDesc.ofDescriptor(descriptor).returnType().descriptorString().equals("V");
    }
}

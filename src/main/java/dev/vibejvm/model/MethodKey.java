package dev.vibejvm.model;

/** Value-typed identity for a method within a class: name + descriptor. */
public record MethodKey(String name, String descriptor) {
    public static MethodKey of(String name, String descriptor) {
        return new MethodKey(name, descriptor);
    }
}

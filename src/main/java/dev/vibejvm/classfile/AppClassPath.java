package dev.vibejvm.classfile;

import dev.vibejvm.error.LinkageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads .class bytes from a directory of compiled classes (the "user classpath"). */
public final class AppClassPath {
    private final Path root;

    public AppClassPath(Path root) {
        this.root = root;
    }

    /** Returns the .class bytes for an internal name, or null if absent. */
    public byte[] read(String internalName) {
        Path p = root.resolve(internalName + ".class");
        if (!Files.exists(p)) return null;
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new LinkageException("failed reading " + p, e);
        }
    }
}

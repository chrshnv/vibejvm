package dev.vibejvm.classfile;

import dev.vibejvm.error.LinkageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads .class bytes from {@code $JAVA_HOME/jmods/java.base.jmod}.
 * <p>
 * A jmod file is a standard ZIP archive with a 4-byte {@code JM\x01\x00} prefix.
 * {@link ZipFile} locates the End-of-Central-Directory by scanning backwards from EOF,
 * so it tolerates the prefix without any stripping. Class entries live at
 * {@code classes/<internal-name>.class}.
 */
public final class BootstrapClassLoader implements AutoCloseable {
    private final ZipFile jmod;

    public BootstrapClassLoader(Path javaHome) {
        Path jmodPath = javaHome.resolve("jmods/java.base.jmod");
        try {
            this.jmod = new ZipFile(jmodPath.toFile());
        } catch (IOException e) {
            throw new LinkageException("cannot open " + jmodPath, e);
        }
    }

    /** Returns the .class bytes for an internal name like "java/lang/Object", or null if absent. */
    public byte[] read(String internalName) {
        ZipEntry entry = jmod.getEntry("classes/" + internalName + ".class");
        if (entry == null) return null;
        try (InputStream in = jmod.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new LinkageException("failed reading " + internalName + " from jmod", e);
        }
    }

    @Override
    public void close() throws IOException {
        jmod.close();
    }
}

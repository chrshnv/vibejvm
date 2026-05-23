package dev.vibejvm;

import dev.vibejvm.classfile.BootstrapClassLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BootstrapClassLoaderTest {
    @Test
    void readsObjectClassFromJmod() throws Exception {
        Path javaHome = Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home")));
        try (BootstrapClassLoader loader = new BootstrapClassLoader(javaHome)) {
            byte[] bytes = loader.read("java/lang/Object");
            assertNotNull(bytes, "java/lang/Object must be present in java.base.jmod");
            assertTrue(bytes.length > 0);
            // Class-file magic 0xCAFEBABE
            assertEquals((byte) 0xCA, bytes[0]);
            assertEquals((byte) 0xFE, bytes[1]);
            assertEquals((byte) 0xBA, bytes[2]);
            assertEquals((byte) 0xBE, bytes[3]);
            // Major version: Java 25 jmod ships major=69. Read big-endian short at offset 6.
            int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            assertEquals(69, major, "expected Java 25 class-file major version");
        }
    }

    @Test
    void missingClassReturnsNull() throws Exception {
        Path javaHome = Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home")));
        try (BootstrapClassLoader loader = new BootstrapClassLoader(javaHome)) {
            assertNull(loader.read("nonexistent/Class"));
        }
    }
}

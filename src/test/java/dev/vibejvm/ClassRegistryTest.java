package dev.vibejvm;

import dev.vibejvm.classfile.AppClassPath;
import dev.vibejvm.classfile.BootstrapClassLoader;
import dev.vibejvm.classfile.ClassRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassRegistryTest {
    @Test
    void resolvingPrintStreamLoadsExactSupertypeChain() {
        Path javaHome = Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home")));
        Path appCp = Path.of(System.getProperty("vibejvm.appClasspath"));
        BootstrapClassLoader boot = new BootstrapClassLoader(javaHome);
        ClassRegistry reg = new ClassRegistry(boot, new AppClassPath(appCp));

        reg.resolve("java/io/PrintStream");

        Set<String> loaded = reg.loadedNames();
        // Exactly PrintStream + its superclass chain to Object. Interfaces are NOT loaded.
        assertEquals(
                Set.of(
                        "java/lang/Object",
                        "java/io/OutputStream",
                        "java/io/FilterOutputStream",
                        "java/io/PrintStream"
                ),
                loaded,
                "PrintStream resolution must load exactly its superclass chain"
        );
    }
}

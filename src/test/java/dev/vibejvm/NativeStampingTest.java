package dev.vibejvm;

import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmMethod;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NativeStampingTest {
    @Test
    void onlyPrintStreamPrintlnStringIsStamped() {
        Vm vm = new Vm(
                Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home"))),
                Path.of(System.getProperty("vibejvm.appClasspath"))
        );
        vm.bootstrap();

        VmClass ps = vm.printStreamClass();

        VmMethod stringPrintln = ps.findMethod(MethodKey.of("println", "(Ljava/lang/String;)V"));
        assertNotNull(stringPrintln);
        assertNotNull(stringPrintln.nativeHandler(),
                "println(String) must be stamped with a native handler");

        VmMethod intPrintln = ps.findMethod(MethodKey.of("println", "(I)V"));
        assertNotNull(intPrintln);
        assertNull(intPrintln.nativeHandler(),
                "println(int) must NOT be stamped");
    }

    @Test
    void systemOutPopulated() {
        Vm vm = new Vm(
                Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home"))),
                Path.of(System.getProperty("vibejvm.appClasspath"))
        );
        vm.bootstrap();

        Object out = vm.getStaticField(vm.systemClass(), "out");
        assertNotNull(out, "System.out must be populated by bootstrap");
    }
}

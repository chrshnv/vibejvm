package dev.vibejvm;

import dev.vibejvm.error.UnsupportedOpcodeException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An error raised by a JIT runtime helper happens inside an FFM upcall, where letting an exception
 * escape would abort the whole VM. It must instead surface to the caller as a normal catchable
 * exception. This locks in that contract: a method whose callee uses an unsupported opcode throws
 * UnsupportedOpcodeException out of {@code vm.run}, rather than terminating the process.
 */
class RuntimeErrorPropagationTest {
    @Test
    void unsupportedOpcodeInACalleeIsCatchableNotFatal() {
        Vm vm = newVm();
        assertThrows(UnsupportedOpcodeException.class,
                () -> vm.run("UnsupportedInCallee", new String[0]));
    }

    private static Vm newVm() {
        return new Vm(
                Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home"))),
                Path.of(System.getProperty("vibejvm.appClasspath"))
        );
    }
}

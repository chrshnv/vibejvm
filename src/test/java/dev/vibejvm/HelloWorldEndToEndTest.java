package dev.vibejvm;

import dev.vibejvm.model.MethodKey;
import dev.vibejvm.model.VmClass;
import dev.vibejvm.model.VmMethod;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HelloWorldEndToEndTest {
    @Test
    void helloWorldPrintsExactlyTheExpectedLine() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(buf, true));
        try {
            Vm vm = newVm();
            vm.run("HelloWorld", new String[0]);
        } finally {
            System.setOut(originalOut);
        }
        // line.separator on macOS is "\n"; assert without trailing newline-sensitivity.
        assertEquals("Hello, World!" + System.lineSeparator(), buf.toString());
    }

    @Test
    void exactlyFourInstructionsRunInMain() {
        Vm vm = newVm();
        // Bootstrap and resolve HelloWorld so we can locate main BEFORE execution.
        vm.bootstrap();
        VmClass hello = vm.classRegistry().resolve("HelloWorld");
        VmMethod main = hello.findMethod(MethodKey.of("main", "([Ljava/lang/String;)V"));
        assertNotNull(main);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(buf, true));
        try {
            vm.run("HelloWorld", new String[0]);
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(4, vm.executedInstructionCount(main),
                "main must execute exactly 4 bytecode instructions; >4 means native dispatch broke");
    }

    private static Vm newVm() {
        return new Vm(
                Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home"))),
                Path.of(System.getProperty("vibejvm.appClasspath"))
        );
    }
}

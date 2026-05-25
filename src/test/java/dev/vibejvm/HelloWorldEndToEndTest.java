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
    void mainIsCompiledOnceAndPrintlnDispatchesOnce() {
        Vm vm = newVm();
        // Bootstrap and resolve the two methods we assert on BEFORE execution.
        vm.bootstrap();
        VmClass hello = vm.classRegistry().resolve("HelloWorld");
        VmMethod main = hello.findMethod(MethodKey.of("main", "([Ljava/lang/String;)V"));
        VmMethod println = vm.printStreamClass()
                .findMethod(MethodKey.of("println", "(Ljava/lang/String;)V"));
        assertNotNull(main);
        assertNotNull(println);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(buf, true));
        try {
            vm.run("HelloWorld", new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        // The JIT pipeline must fire exactly once each way: main is compiled a single time, and
        // the println native handler is dispatched a single time. >1 compile means the cache broke;
        // a native count != 1 means dispatch entered (or skipped) real PrintStream bytecode.
        assertEquals(1, vm.compileCount(main), "main must be JIT-compiled exactly once");
        assertEquals(1, vm.nativeInvocationCount(println),
                "println native handler must fire exactly once");
        // Bonus, in the spirit of the old anti-cheat count: the JIT really lowered main's 4 bytecodes
        // (getstatic, ldc, invokevirtual, return) rather than short-circuiting.
        assertEquals(4, vm.compiledInstructionCount(main),
                "main compiles to exactly 4 guest bytecode instructions");
    }

    private static Vm newVm() {
        return new Vm(
                Path.of(System.getProperty("vibejvm.javaHome", System.getProperty("java.home"))),
                Path.of(System.getProperty("vibejvm.appClasspath"))
        );
    }
}

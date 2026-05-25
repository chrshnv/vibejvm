# VibeJVM

A small Java Virtual Machine written in Java 25. It loads a compiled `.class`
file, parses real `java.base` classes from `$JAVA_HOME/jmods/java.base.jmod`,
and **JIT-compiles bytecode to native AArch64 machine code** — emitted into
executable memory and called through the Foreign Function & Memory API — well
enough to run:

```java
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

There is no interpreter. Every guest method is compiled on first call and runs
as native code. **Platform: macOS on Apple Silicon (AArch64) only** — the
backend hand-encodes AArch64 and relies on the host JVM's
`allow-unsigned-executable-memory` entitlement.

## Run it

```bash
./gradlew run                  # prints: Hello, World!
./gradlew run -PvibejvmTrace   # same, dumping each emitted native template (hex) on stderr
./gradlew test                 # runs the test suite
./gradlew installDist          # standalone distribution in build/install/vibejvm
```

The standalone script needs the two system properties through `JAVA_OPTS`:

```bash
JAVA_OPTS="-Dvibejvm.appClasspath=build/fixtures/classes \
           -Dvibejvm.javaHome=$JAVA_HOME" \
  build/install/vibejvm/bin/vibejvm HelloWorld
```

## How it works

`HelloWorld.main` compiles to exactly four bytecodes:

```
0: getstatic     java/lang/System.out:Ljava/io/PrintStream;
3: ldc           "Hello, World!"
5: invokevirtual java/io/PrintStream.println:(Ljava/lang/String;)V
8: return
```

VibeJVM compiles that sequence to native code and executes it for real. The pipeline:

1. **Parse** — `java.lang.classfile` (JEP 484) reads `HelloWorld.class` and
   the four real `java.base` classes we need (`Object`, `String`, `System`,
   and the `PrintStream → FilterOutputStream → OutputStream` chain). The
   loader opens `java.base.jmod` directly with `java.util.zip.ZipFile`; the
   4-byte `JM\x01\x00` prefix is tolerated transparently.
2. **Link** — `ClassRegistry` resolves the superclass chain on demand. Each
   class gets a slot layout (static slots on the class, instance slots
   flattened across the hierarchy). No `<clinit>` runs (see below).
3. **Stamp natives** — `NativeRegistry` binds one handler:
   `java/io/PrintStream.println(Ljava/lang/String;)V` → host `System.out.println`.
   After link, matching `VmMethod`s get the handler stamped onto them so
   dispatch never enters real `PrintStream` bytecode.
4. **Bootstrap** — `Vm.bootstrap()` fabricates a synthetic `PrintStream`
   `VmObject` (no constructor runs) and writes it directly into
   `System`'s static-field slot for `out`. Because we never emit `putstatic`,
   the `final` on `System.out` is not in the way.
5. **Compile** — `Jit` walks `CodeModel` from the classfile API and emits an
   AArch64 template per `Instruction` subtype with `Aarch64Assembler`:
   `FieldInstruction` (`getstatic`), `ConstantInstruction` (`ldc` of a
   `StringEntry`), `InvokeInstruction` (the three `invoke`s), `ReturnInstruction`,
   plus `aload`/`astore`/`pop`/`dup`/`nop`. Operand-stack heights are known
   statically, so every value lives at a fixed slot in an off-heap frame buffer
   (addressed through `x19`) — no runtime stack pointer. Semantic work (static
   reads, string interning, call dispatch) is done by runtime helpers reached
   through FFM **upcall stubs**; object references cross the native boundary only
   as `int` handles into a `HandleTable`, never as raw pointers.
6. **Run** — `JitMemory` commits the code into an executable page (`mmap`
   read-write, copy, `mprotect` read-execute, flush the I-cache), and a
   `(frameBase) -> int` downcall handle invokes it. `invokevirtual println`
   lands in the stamped native handler; a call to a non-native method compiles
   that callee on first call and invokes its native code in turn. An off-script
   opcode has no template and throws `UnsupportedOpcodeException` — loud, not silent.

### The one deliberate cheat

A faithful `java.lang.System.<clinit>` would pull in `jdk.internal.misc.Unsafe`,
the module system, `Properties`, `ThreadGroup`, the charset machinery, and
dozens of native methods — a multi-week project. v1 instead skips all class
initialization and pre-populates `System.out` from outside the guest. Real
class files are still loaded, real bytecode is still executed; only this one
seam is mocked.

### Anti-cheat: how we know it's not faking it

The `HelloWorldEndToEndTest.mainIsCompiledOnceAndPrintlnDispatchesOnce` test
asserts that the JIT pipeline fires exactly once each way: `main` is compiled a
single time, and the `println` native handler is dispatched a single time. As a
bonus it checks that `main` lowered to exactly **4** guest bytecodes
(`getstatic`/`ldc`/`invokevirtual`/`return`) — proving real bytecode was
compiled, not string-matched or shortcut. If native dispatch broke and we
actually entered real `PrintStream.println` bytecode, the println count would be
0 (and the run would crash on the synthetic out's null instance fields).
Run with `-PvibejvmTrace` to see the emitted machine-code words for each template.

## Layout

```
src/
├── main/java/dev/vibejvm/
│   ├── Main.java                          CLI entry point
│   ├── Vm.java                            bootstrap + run
│   ├── classfile/
│   │   ├── BootstrapClassLoader.java      ZipFile over java.base.jmod
│   │   ├── AppClassPath.java              dir of .class files
│   │   ├── ClassParser.java               java.lang.classfile wrapper
│   │   └── ClassRegistry.java             load + link, no init
│   ├── model/                             VmClass/Method/Field/Object/String/Array
│   ├── jit/                               Jit (templates) + Aarch64Assembler + JitMemory
│   │                                      + HandleTable + CompiledMethod
│   ├── nativeimpl/                        NativeRegistry + PrintStream intercept
│   └── error/                             LinkageException, UnsupportedOpcodeException, ...
├── test/java/dev/vibejvm/                 JUnit 5 tests
└── fixtures/java/HelloWorld.java          built into build/fixtures/classes by Gradle
```

## What's intentionally NOT here (v1 boundaries)

- Class initialization (`<clinit>`)
- Bytecode verification (stackmap frames are ignored)
- Garbage collection (host JVM handles `VmObject` lifetime)
- Threading, synchronization, monitors
- Exception handling, `athrow`
- Reflection, `MethodHandle`, `invokedynamic`
- Real `String` internals — `VmString.javaValue` is a host `String`; any
  bytecode that touches the real `byte[] value` will crash loudly
- Interfaces, `invokeinterface`, `instanceof`, `checkcast`
- Arrays beyond the length-0 `String[]` passed to `main`
- Arithmetic and branch opcodes (no `iadd`, `if*`, `goto`, `tableswitch`, …)
- Any non-AArch64 / non-macOS backend, register allocation beyond fixed frame
  slots, inlining, deoptimization, and reclamation of code pages / handles

Anything outside the happy path throws `UnsupportedOpcodeException`,
`LinkageException`, or `NativeMissingException` — explicit failure is the
v1 design choice.

## Requirements

- macOS on Apple Silicon (AArch64). The JIT hand-encodes AArch64 and commits it
  to executable memory; there is no other backend.
- JDK 25 (the project's Gradle toolchain pins it; the system `javac 25` also
  compiles the fixture). The launcher passes `--enable-native-access=ALL-UNNAMED`
  for the Foreign Function & Memory API, and the host JVM must allow unsigned
  executable memory (stock distributions on macOS do).
- Gradle 9.5+ (`./gradlew` bootstraps it).

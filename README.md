# VibeJVM

A small Java Virtual Machine written in Java 25. It loads a compiled `.class`
file, parses real `java.base` classes from `$JAVA_HOME/jmods/java.base.jmod`,
and interprets bytecode end-to-end well enough to run:

```java
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

## Run it

```bash
./gradlew run                  # prints: Hello, World!
./gradlew run -PvibejvmTrace   # same, with per-instruction trace on stderr
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

VibeJVM executes that sequence for real. The pipeline:

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
5. **Interpret** — `Interpreter` walks `CodeModel.elementStream()` from the
   classfile API and dispatches by `Instruction` subtype:
   `FieldInstruction` (`getstatic`), `ConstantInstruction` (`ldc` of a
   `StringEntry` → interned `VmString`), `InvokeInstruction` (`invokevirtual`
   → native handler or recursive frame), `ReturnInstruction`. A few cheap
   defensive ops (`aload`/`iload`/`astore`/`pop`/`dup`/`nop`/`iconst_*`/
   `bipush`/`sipush`) are wired so an off-script opcode crashes loudly rather
   than silently misbehaves.

### The one deliberate cheat

A faithful `java.lang.System.<clinit>` would pull in `jdk.internal.misc.Unsafe`,
the module system, `Properties`, `ThreadGroup`, the charset machinery, and
dozens of native methods — a multi-week project. v1 instead skips all class
initialization and pre-populates `System.out` from outside the guest. Real
class files are still loaded, real bytecode is still executed; only this one
seam is mocked.

### Anti-cheat: how we know it's not faking it

The `HelloWorldEndToEndTest.exactlyFourInstructionsRunInMain` test asserts
that `HelloWorld.main` executes exactly **4** bytecode instructions during a
run. If `getstatic`/`ldc`/`invokevirtual`/`return` were being string-matched
or shortcut, the count would be 0 or 1. If native dispatch broke and we
actually entered real `PrintStream.println` bytecode, the count would be much
higher (and the run would crash on the synthetic out's null instance fields).
4 is the only correct answer.

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
│   ├── interp/                            Frame + Interpreter (the switch loop)
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

Anything outside the happy path throws `UnsupportedOpcodeException`,
`LinkageException`, or `NativeMissingException` — explicit failure is the
v1 design choice.

## Requirements

- JDK 25 (the project's Gradle toolchain pins it; the system `javac 25` also
  compiles the fixture).
- Gradle 9.5+ (`./gradlew` bootstraps it).

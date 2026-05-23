# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What VibeJVM is

A JVM written in Java 25 that interprets real compiled `.class` bytecode and
loads real classes from `$JAVA_HOME/jmods/java.base.jmod`. v1 goal is
`HelloWorld` end-to-end; see `README.md` for the full pipeline and v1
boundaries. The rest of this file is the stuff you need to know that the
README doesn't say.

## Commands

```bash
./gradlew run                       # build + run HelloWorld; prints "Hello, World!"
./gradlew run -PvibejvmTrace        # same, with per-instruction trace on stderr
./gradlew test                      # full test suite (JUnit 5)
./gradlew test --tests dev.vibejvm.HelloWorldEndToEndTest.exactlyFourInstructionsRunInMain
./gradlew compileFixtures           # rebuild HelloWorld.class only
./gradlew installDist               # standalone bin/vibejvm script
```

`run` and `test` depend on `compileFixtures` and pass two system properties:
`-Dvibejvm.appClasspath=<build/fixtures/classes>` and
`-Dvibejvm.javaHome=<host JAVA_HOME>`. Anything that constructs `Vm` outside
those tasks must set both. The `installDist` script reads them from
`JAVA_OPTS`, not from CLI args.

## Architecture seams (where to make changes)

The codebase is small but the seams are not all obvious. Map each common
extension to the right file:

| You want to... | Touch this |
|---|---|
| Implement a new opcode | `interp/Interpreter.java::dispatch` — add a `case` for the relevant `Instruction` subtype from `java.lang.classfile`. Do not decode raw bytes; the classfile API hands you structured `FieldInstruction` / `InvokeInstruction` / `ConstantInstruction` / etc. with the constant-pool entries already resolved. |
| Intercept a JDK method (native shim) | `nativeimpl/PrintStreamNatives.java` is the template. Register `(owner, name, descriptor) -> NativeHandler` in `Vm.bootstrap` (before the stamping loop), and the handler will be stamped onto any matching `VmMethod` by `NativeRegistry.stamp`. |
| Load a new JDK class eagerly | `Vm.bootstrap()` resolves Object/String/System/PrintStream. Add `classRegistry.resolve("…")` there, then re-run the stamp loop. Lazy resolution from inside the interpreter also works but won't get re-stamped — only `Vm.run` re-stamps the main class. |
| Change class init policy | `classfile/ClassRegistry.java`. v1 deliberately never runs `<clinit>`; the TODO marker lives there. This is the single biggest deviation from the JVM spec — any change here cascades. |
| Add an app-classpath source | `classfile/AppClassPath.java` reads from one dir. `ClassRegistry.readBytes` routes `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/` names to the bootstrap loader; everything else is app-first with bootstrap fallback. |

## Tight couplings to be aware of

- **`Vm` ↔ `Interpreter` is circular by design.** `Vm` owns the `Interpreter`;
  the interpreter calls back to `Vm` for class resolution, string interning,
  and the executed-instruction counter that the anti-cheat test reads. Don't
  try to factor this out; the counter is how we prove native dispatch fires.

- **The anti-cheat test will fail if you add opcodes that run in `HelloWorld.main`.**
  `HelloWorldEndToEndTest.exactlyFourInstructionsRunInMain` asserts exactly 4
  bytecode instructions execute in `main`. Adding new opcodes is fine; making
  them fire in `HelloWorld.main` is not. If you intentionally change the
  fixture, update the expected count too.

- **Synthetic `System.out` has no instance fields populated.** Its `vmClass`
  is the real `PrintStream`, but no constructor ran. The moment you let
  interpretation actually enter real `PrintStream` bytecode (e.g. by removing
  the native stamping for `println(String)V` or adding any other `PrintStream`
  call without a native), you will hit nulls on `OutputStream.out` and crash.
  Either keep the native intercept or build a real `new` + `<init>` path
  (which v1 explicitly defers).

- **`String` constants bypass `String.<init>`.** `ldc` of a `CONSTANT_String`
  goes through `Vm.internString` and constructs a `VmString` directly with a
  host `java.lang.String` payload. Don't add a path that calls
  `java.lang.String`'s constructor — none of the compact-string slots
  (`byte[] value`, `byte coder`) are wired.

- **`MemberRefEntry.nameAndType().type()` not `MemberRefEntry.type()`.** The
  classfile API splits this; if you copy-paste call resolution code, keep
  the indirection.

## Code style

- This is a greenfield single-author codebase with a clear v1 scope. Don't
  add backwards-compat shims, feature flags, or speculative abstractions.
- Comments are sparse and only mark *why* (non-obvious invariants, the
  deliberate cheats, JEP/JVMS references). Don't add what-comments.
- Errors throw one of three named exceptions in `error/`:
  `LinkageException` (class/method/field resolution), `UnsupportedOpcodeException`
  (interpreter saw something it doesn't handle), `NativeMissingException`
  (a registered native got the wrong argument type). Loud failure is the v1
  design choice — don't swallow.

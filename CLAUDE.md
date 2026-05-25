# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What VibeJVM is

A JVM written in Java 25 that **JIT-compiles** real compiled `.class` bytecode
to **native AArch64 machine code** and loads real classes from
`$JAVA_HOME/jmods/java.base.jmod`. There is no interpreter: every guest method
is compiled on first call and executed from executable memory via the Foreign
Function & Memory API. v1 goal is `HelloWorld` end-to-end; see `README.md` for
the full pipeline and v1 boundaries. The rest of this file is the stuff you
need to know that the README doesn't say.

**Platform: macOS on Apple Silicon (AArch64) only.** The backend hand-encodes
AArch64 and relies on the host JVM's `allow-unsigned-executable-memory`
entitlement. Porting to x86/Linux is out of v1 scope (single-target, same
spirit as the `<clinit>` cheat).

## Commands

```bash
./gradlew run                       # build + run HelloWorld; prints "Hello, World!"
./gradlew run -PvibejvmTrace        # same, dumping each emitted native template (hex) on stderr
./gradlew test                      # full test suite (JUnit 5)
./gradlew test --tests dev.vibejvm.HelloWorldEndToEndTest.mainIsCompiledOnceAndPrintlnDispatchesOnce
./gradlew compileFixtures           # rebuild HelloWorld.class only
./gradlew installDist               # standalone bin/vibejvm script
```

`run` and `test` depend on `compileFixtures` and pass two system properties:
`-Dvibejvm.appClasspath=<build/fixtures/classes>` and
`-Dvibejvm.javaHome=<host JAVA_HOME>`. Anything that constructs `Vm` outside
those tasks must set both. All run paths also pass
`--enable-native-access=ALL-UNNAMED` (FFM restricted methods); this is wired
into `applicationDefaultJvmArgs`, the `run` task, and the `test` task. The
`installDist` script reads the system properties from `JAVA_OPTS`, not from CLI
args, and inherits the native-access flag from the default JVM args.

## Architecture seams (where to make changes)

The codebase is small but the seams are not all obvious. Map each common
extension to the right file:

| You want to... | Touch this |
|---|---|
| Implement a new opcode | `jit/Jit.java::emit` — add a `case` for the relevant `Instruction` subtype from `java.lang.classfile` and emit a native template via `Aarch64Assembler`. Do not decode raw bytes; the classfile API hands you structured `FieldInstruction` / `InvokeInstruction` / `ConstantInstruction` / etc. with the constant-pool entries already resolved. `emit` must keep the abstract operand-stack `depth` correct (it returns the new depth) — slot offsets are derived from it. Anything semantic (heap/dispatch) goes in a runtime helper reached by an upcall stub, not in the emitted code. |
| Add a native instruction form | `jit/Aarch64Assembler.java` — add an encoder method; comment its ARM-ARM form and base encoding. |
| Intercept a JDK method (native shim) | `nativeimpl/PrintStreamNatives.java` is the template. Register `(owner, name, descriptor) -> NativeHandler` in `Vm.bootstrap` (before the stamping loop), and the handler will be stamped onto any matching `VmMethod` by `NativeRegistry.stamp`. The JIT's `invoke` helper routes to a stamped handler instead of compiling+calling the target. |
| Load a new JDK class eagerly | `Vm.bootstrap()` resolves Object/String/System/PrintStream. Add `classRegistry.resolve("…")` there, then re-run the stamp loop. Lazy resolution from inside a runtime helper also works but won't get re-stamped — only `Vm.run` re-stamps the main class. |
| Change class init policy | `classfile/ClassRegistry.java`. v1 deliberately never runs `<clinit>`; the TODO marker lives there. This is the single biggest deviation from the JVM spec — any change here cascades. |
| Add an app-classpath source | `classfile/AppClassPath.java` reads from one dir. `ClassRegistry.readBytes` routes `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/` names to the bootstrap loader; everything else is app-first with bootstrap fallback. |

## Tight couplings to be aware of

- **`Vm` ↔ `Jit` is circular by design.** `Vm` owns the `Jit`; the JIT's
  runtime helpers call back to `Vm` for class resolution, string interning, and
  native dispatch. `Vm` exposes `compileCount` / `nativeInvocationCount` /
  `compiledInstructionCount`, which delegate to counters in `Jit` — these are
  how the anti-cheat test proves the pipeline fired. Don't try to factor this out.

- **Runtime helpers must never let an exception cross the upcall boundary.** `getStatic` /
  `ldcString` / `invoke` run as FFM upcalls; an exception escaping one aborts the whole VM
  (HotSpot can't unwind a Java exception through native frames). So each helper catches
  `Throwable`, stashes the first error in `Jit.pendingError`, and returns a sentinel `0`;
  the top-level downcall (`Jit.run`) calls `checkPendingError()` to re-raise it on the Java
  side. `invokeCompiled` follows the same rule (it runs inside the `invoke` upcall). Any new
  helper or any new work added to these must keep that catch-and-stash shape, or runtime errors
  become VM aborts instead of catchable `UnsupportedOpcodeException` / `LinkageException` /
  `NativeMissingException`.

- **Native code only ever sees `int` handles, never object pointers.** References
  cross into emitted code as indices into `jit/HandleTable`. The off-heap frame
  buffer holds 64-bit slots (one per local / operand); helper results are stored
  with a zero-extend (`zeroExtendW`) so the high 32 bits stay clean for a 64-bit
  store. Operand-stack heights are static, so each value has a fixed slot offset
  — there is no runtime stack pointer. If you add an opcode whose stack effect you
  get wrong, `emit`'s `depth` drifts and every later slot offset is corrupt.

- **The anti-cheat test asserts the JIT pipeline, not an instruction count.**
  `HelloWorldEndToEndTest.mainIsCompiledOnceAndPrintlnDispatchesOnce` asserts
  `main` is compiled exactly once and the `println` native fires exactly once
  (plus a bonus: `main` lowers to exactly 4 guest bytecodes). Adding opcodes is
  fine; changing how often `main` compiles or dispatches natively is not. If you
  intentionally change the fixture, update the expectations.

- **Executable memory is `mmap(RW)` → write → `mprotect(RX)` → icache flush.**
  See `jit/JitMemory`. The icache flush is mandatory on ARM. The MAP_JIT +
  `pthread_jit_write_protect_np` approach was tried first and faulted under FFM
  on this JVM; mprotect is the working path. Committed pages are never unmapped
  (accepted v1 leak), as are `HandleTable` entries.

- **Synthetic `System.out` has no instance fields populated.** Its `vmClass`
  is the real `PrintStream`, but no constructor ran. The moment you let compiled
  code actually enter real `PrintStream` bytecode (e.g. by removing the native
  stamping for `println(String)V` or adding any other `PrintStream` call without
  a native), you will hit nulls on `OutputStream.out` and crash. Either keep the
  native intercept or build a real `new` + `<init>` path (which v1 defers).

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
  `LinkageException` (class/method/field resolution, also JIT memory/codegen
  failures), `UnsupportedOpcodeException` (the compiler has no template for an
  instruction), `NativeMissingException` (a registered native got the wrong
  argument type). Loud failure is the v1 design choice — don't swallow.

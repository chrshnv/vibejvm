package dev.vibejvm.jit;

import dev.vibejvm.error.LinkageException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Allocates executable memory and commits machine code into it. macOS/AArch64 forbids a page
 * that is simultaneously writable and executable, so we mmap the page read-write, copy the code,
 * flip it to read-execute with {@code mprotect}, then flush the instruction cache (mandatory on
 * ARM — otherwise the CPU may execute stale instructions). This relies on the host JVM running
 * with the {@code com.apple.security.cs.allow-unsigned-executable-memory} entitlement (it does).
 *
 * Committed pages are never unmapped — another accepted v1 leak (one tiny page per compiled
 * method). AArch64/macOS only; this is the single-target backend, in the spirit of the v1 cheats.
 */
final class JitMemory {
    private static final int PROT_READ = 0x1, PROT_WRITE = 0x2, PROT_EXEC = 0x4;
    private static final int MAP_PRIVATE = 0x2, MAP_ANON = 0x1000;
    private static final long PAGE = 4096;

    private final MethodHandle mmap;        // void* mmap(void*, size_t, int, int, int, off_t)
    private final MethodHandle mprotect;    // int   mprotect(void*, size_t, int)
    private final MethodHandle icache;      // void  sys_icache_invalidate(void*, size_t)

    JitMemory() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        this.mmap = linker.downcallHandle(libc.find("mmap").orElseThrow(),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        this.mprotect = linker.downcallHandle(libc.find("mprotect").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
        this.icache = linker.downcallHandle(libc.find("sys_icache_invalidate").orElseThrow(),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
    }

    /** Copy {@code code} into a fresh executable page and return the entry-point segment. */
    MemorySegment commit(byte[] code) {
        long len = (code.length + PAGE - 1) & ~(PAGE - 1);
        try {
            MemorySegment p = (MemorySegment) mmap.invoke(MemorySegment.NULL, len,
                    PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0L);
            if (p.address() == -1L) {
                throw new LinkageException("mmap failed for JIT code page");
            }
            MemorySegment region = p.reinterpret(len);
            MemorySegment.copy(MemorySegment.ofArray(code), 0, region, 0, code.length);
            int rc = (int) mprotect.invoke(region, len, PROT_READ | PROT_EXEC);
            if (rc != 0) {
                throw new LinkageException("mprotect RX failed for JIT code (rc=" + rc + ")");
            }
            icache.invoke(region, len);
            return region;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new LinkageException("failed committing JIT code", t);
        }
    }
}

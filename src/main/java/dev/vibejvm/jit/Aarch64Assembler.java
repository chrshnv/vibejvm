package dev.vibejvm.jit;

import dev.vibejvm.error.UnsupportedOpcodeException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal AArch64 instruction encoder for the template JIT. It emits only the handful of
 * instruction forms the templates need, as little-endian 32-bit words. Register arguments are
 * raw numbers 0..30; 31 denotes SP or XZR depending on the instruction. Each emitter comments
 * the ARM-ARM form and the fixed base encoding it specialises — those bit-layouts are the only
 * non-obvious thing here.
 */
final class Aarch64Assembler {
    static final int X0 = 0, X1 = 1, X2 = 2, X9 = 9, X19 = 19;

    private final List<Integer> words = new ArrayList<>();

    private void emit(int word) { words.add(word); }

    /** Function prologue: save fp/lr and the callee-saved x19 (we use it as the frame base). */
    void prologue() {
        emit(0xA9BE7BFD);   // stp x29, x30, [sp, #-32]!   (STP pre-index, 64-bit)
        emit(0xF9000BF3);   // str x19, [sp, #16]
        emit(0x910003FD);   // mov x29, sp                 (add x29, sp, #0)
    }

    /** Restore the saved registers and return. {@code blr} clobbers x30, so lr must be reloaded. */
    void epilogue() {
        emit(0xF9400BF3);   // ldr x19, [sp, #16]
        emit(0xA8C27BFD);   // ldp x29, x30, [sp], #32     (LDP post-index, 64-bit)
        emit(0xD65F03C0);   // ret
    }

    /** mov Xd, Xm  — 64-bit register move (ORR Xd, XZR, Xm). */
    void movReg(int rd, int rm) {
        emit(0xAA0003E0 | (rm << 16) | rd);
    }

    /** mov Wd, Wm — 32-bit move that zero-extends into Xd (clears bits 63:32). */
    void zeroExtendW(int rd, int rm) {
        emit(0x2A0003E0 | (rm << 16) | rd);
    }

    /** movz Wd, #imm16 — load a 16-bit immediate into a w-register (zero-extends into Xd). */
    void movImm16(int rd, int imm16) {
        if (imm16 < 0 || imm16 > 0xFFFF) {
            // A single MOVZ only carries 16 bits; silently masking would miscompile (wrong
            // constant-pool id / arg slot). The v1 JIT refuses the method instead.
            throw new UnsupportedOpcodeException(
                    "immediate " + imm16 + " does not fit in a 16-bit movz (v1 JIT limit)");
        }
        emit(0x52800000 | (imm16 << 5) | rd);
    }

    /** Materialise a full 64-bit constant into Xd via movz + 3× movk (one per 16-bit lane). */
    void movImm64(int rd, long v) {
        emit(0xD2800000 | (int) ((v & 0xFFFF) << 5) | rd);            // movz xd, #v0
        emit(0xF2A00000 | (int) (((v >>> 16) & 0xFFFF) << 5) | rd);   // movk xd, #v1, lsl #16
        emit(0xF2C00000 | (int) (((v >>> 32) & 0xFFFF) << 5) | rd);   // movk xd, #v2, lsl #32
        emit(0xF2E00000 | (int) (((v >>> 48) & 0xFFFF) << 5) | rd);   // movk xd, #v3, lsl #48
    }

    /** The LDR/STR unsigned-offset imm12 field is 12 bits; offset = slot*8, so slot must fit 0..4095. */
    private static int checkSlot(int slot) {
        if (slot < 0 || slot > 0xFFF) {
            throw new UnsupportedOpcodeException(
                    "frame slot " + slot + " exceeds the 12-bit ldr/str offset (v1 JIT limit)");
        }
        return slot;
    }

    /** str Xt, [Xn, #slot*8] — STR unsigned-offset, 64-bit; imm12 == slot (offset is slot*8). */
    void strSlot(int rt, int rn, int slot) {
        emit(0xF9000000 | (checkSlot(slot) << 10) | (rn << 5) | rt);
    }

    /** ldr Xt, [Xn, #slot*8] — LDR unsigned-offset, 64-bit. */
    void ldrSlot(int rt, int rn, int slot) {
        emit(0xF9400000 | (checkSlot(slot) << 10) | (rn << 5) | rt);
    }

    /** blr Xn — branch with link to the address in Xn (clobbers x30). */
    void blr(int rn) {
        emit(0xD63F0000 | (rn << 5));
    }

    int instructionCount() { return words.size(); }

    int[] words() {
        int[] copy = new int[words.size()];
        for (int i = 0; i < copy.length; i++) copy[i] = words.get(i);
        return copy;
    }

    byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(words.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int w : words) bb.putInt(w);
        return bb.array();
    }
}

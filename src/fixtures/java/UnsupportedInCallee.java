// main() uses only JIT-supported opcodes (invokestatic, pop, return), so it compiles fine; the
// unsupported opcode (a non-string constant) is in helper(), which the JIT only compiles when the
// invoke runtime helper is reached at run time — i.e. the error is raised *inside* an FFM upcall.
// Regression fixture for: such errors must surface as a catchable exception, not abort the VM.
public class UnsupportedInCallee {
    public static void main(String[] args) {
        helper();
    }

    static int helper() {
        return 5; // iconst_5 -> ConstantInstruction the v1 JIT does not template
    }
}

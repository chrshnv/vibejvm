package dev.vibejvm;

import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
    public static void main(String[] args) throws Exception {
        String javaHomeProp = System.getProperty("vibejvm.javaHome", System.getProperty("java.home"));
        String appCpProp = System.getProperty("vibejvm.appClasspath");
        if (appCpProp == null) {
            System.err.println("vibejvm: missing -Dvibejvm.appClasspath=<dir of .class files>");
            System.exit(2);
        }
        String mainClass = args.length > 0 ? args[0] : "HelloWorld";
        String[] userArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        new Vm(Path.of(javaHomeProp), Path.of(appCpProp)).run(mainClass, userArgs);
    }
}

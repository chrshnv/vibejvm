plugins {
    application
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The JIT emits native code and calls it via the Foreign Function & Memory API, which means
// restricted (downcall/upcall/reinterpret) operations. Grant native access everywhere the VM runs:
// the application launcher (so installDist / JAVA_OPTS inherit it), the run task, and tests.
val nativeAccessArgs = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass = "dev.vibejvm.Main"
    applicationDefaultJvmArgs = nativeAccessArgs
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(nativeAccessArgs)
}

// --- Fixture compilation -----------------------------------------------------
val fixturesSrc = layout.projectDirectory.dir("src/fixtures/java")
val fixturesOut = layout.buildDirectory.dir("fixtures/classes")

val compileFixtures = tasks.register<JavaCompile>("compileFixtures") {
    source(fixturesSrc)
    include("**/*.java")
    destinationDirectory.set(fixturesOut)
    classpath = files()
    options.release.set(25)
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(25)
    })
}

tasks.named<JavaExec>("run") {
    dependsOn(compileFixtures)
    jvmArgs(nativeAccessArgs)
    systemProperty("vibejvm.appClasspath", fixturesOut.get().asFile.absolutePath)
    systemProperty("vibejvm.javaHome", System.getProperty("java.home"))
    if (project.hasProperty("vibejvmTrace")) {
        systemProperty("vibejvm.trace", "true")
    }
    args = listOf("HelloWorld")
}

tasks.test {
    dependsOn(compileFixtures)
    systemProperty("vibejvm.appClasspath", fixturesOut.get().asFile.absolutePath)
    systemProperty("vibejvm.javaHome", System.getProperty("java.home"))
}

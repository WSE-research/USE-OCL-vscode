package de.htwk.uselsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.tzi.use.uml.mm.MModel;

/**
 * Shared fixtures: the samples in ../test/samples (also used by the LSP and VS Code tests) and one
 * checker for all tests, because USE has global state.
 */
final class TestSupport {

    static final Path SAMPLES = Path.of("..", "test", "samples").toAbsolutePath().normalize();

    private static UseChecker checker;

    private TestSupport() {
    }

    /** The checker as the server creates it: with output capture and the OCL extensions of use.home. */
    static synchronized UseChecker checker() {
        if (checker == null) {
            Capture.install();
            checker = new UseChecker(Main.useHome());
        }
        return checker;
    }

    static String sample(String name) {
        try {
            return Files.readString(SAMPLES.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A sample model compiled by USE; null if it has errors. */
    static MModel model(String name) {
        return checker().compile(sample(name), name, SAMPLES.resolve(name).toUri());
    }

    /** Result of a JVM started with this test's classpath. */
    record Run(int exitCode, String stdout, String stderr) {
    }

    /** Starts a class of the server in a new JVM, as build.sh's tests and the extension do. */
    static Process start(String mainClass, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Duse.home=" + System.getProperty("use.home", ""), "-cp",
                System.getProperty("java.class.path"), mainClass));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).start();
    }

    static Run run(String mainClass, String... args) throws IOException, InterruptedException {
        Process p = start(mainClass, args);
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException(mainClass + " did not finish");
        }
        return new Run(p.exitValue(), out, err);
    }
}

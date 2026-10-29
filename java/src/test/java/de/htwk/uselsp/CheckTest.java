package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The command line checker, in its own JVM because it ends with System.exit. */
class CheckTest {

    private static String sample(String name) {
        return TestSupport.SAMPLES.resolve(name).toString();
    }

    @Test
    void validFilesPrintNothingAndSucceed() throws Exception {
        TestSupport.Run run = TestSupport.run("de.htwk.uselsp.Check", sample("bibliothek.use"),
                sample("bibliothek.soil"));
        assertEquals("", run.stdout());
        assertEquals(0, run.exitCode());
    }

    @Test
    void faultyFilesPrintOneLinePerMessageAndFail() throws Exception {
        String model = sample("bibliothek-fehler.use");
        String script = sample("bibliothek-fehler.soil");
        TestSupport.Run run = TestSupport.run("de.htwk.uselsp.Check", model, script);
        List<String> lines = run.stdout().lines().toList();
        assertEquals(10, lines.size(), run.stdout());
        assertTrue(lines.contains(model + ":8:14: error: Expected type name, found `Boolen'."), run.stdout());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith(model + ":34:14: warning: ")), run.stdout());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith(script + ":6:1: error: Unknown command `hilfe'")),
                run.stdout());
        assertEquals(1, run.exitCode());
    }

    @Test
    void warningsAloneSucceed() throws Exception {
        Path file = Files.createTempFile("warnung", ".use");
        try {
            Files.writeString(file, "model M\nclass A\nend\nconstraints\n"
                    + "context A inv W:\n  Set{1, 2}->includes('x')\n");
            TestSupport.Run run = TestSupport.run("de.htwk.uselsp.Check", file.toString());
            assertTrue(run.stdout().contains(":6:14: warning: "), run.stdout());
            assertEquals(0, run.exitCode());
        } finally {
            Files.delete(file);
        }
    }
}

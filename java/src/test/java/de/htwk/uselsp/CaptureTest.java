package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaptureTest {

    @BeforeAll
    static void install() {
        Capture.install();
        Capture.install(); // a second call changes nothing
    }

    @Test
    void outputOfTheActionIsCaptured() {
        String out = Capture.during(() -> {
            System.out.print("out ");
            System.err.print("err");
            System.out.write('!');
        });
        assertEquals("out err!", out);
    }

    @Test
    void outputOfOtherThreadsIsNotCaptured() throws InterruptedException {
        AtomicReference<String> inner = new AtomicReference<>();
        String out = Capture.during(() -> {
            Thread t = new Thread(() -> System.out.print("other thread"));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.print("mine");
            inner.set("done");
        });
        assertEquals("mine", out);
        assertEquals("done", inner.get());
    }

    @Test
    void captureEndsWhenTheActionFails() {
        assertThrows(IllegalStateException.class, () -> Capture.during(() -> {
            System.out.print("lost");
            throw new IllegalStateException("USE failed");
        }));
        System.out.print("after the failure ");
        System.out.flush();
        assertEquals("next", Capture.during(() -> System.out.print("next")));
    }

    @Test
    void outputOutsideACaptureGoesToStderr() {
        // nothing to compare: the output must neither throw nor end up in the next capture
        System.out.println("written outside a capture");
        System.out.flush();
        assertEquals("", Capture.during(() -> {
        }));
    }
}

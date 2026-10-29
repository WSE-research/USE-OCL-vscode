package de.htwk.uselsp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures what USE writes to System.out and System.err: during a check, output
 * from the checking thread goes to a buffer, everything else to stderr. USE reports
 * some warnings only via {@code org.tzi.use.util.Log}, which writes to System.out,
 * i.e. right into the protocol channel.
 */
final class Capture extends OutputStream {

    private static Capture instance;

    private final PrintStream stderr;
    private Thread owner;
    private ByteArrayOutputStream buffer;

    private Capture(PrintStream stderr) {
        this.stderr = stderr;
    }

    /** Redirects System.out and System.err; call before the first USE class loads. */
    static synchronized void install() {
        if (instance != null) {
            return;
        }
        instance = new Capture(System.err);
        PrintStream stream = new PrintStream(instance, true, StandardCharsets.UTF_8);
        System.setOut(stream);
        System.setErr(stream);
    }

    /** Runs {@code action} and returns what USE printed while doing so. */
    static String during(Runnable action) {
        Capture c = instance;
        if (c == null) {
            action.run();
            return "";
        }
        ByteArrayOutputStream mine = new ByteArrayOutputStream();
        synchronized (c) {
            c.owner = Thread.currentThread();
            c.buffer = mine;
        }
        try {
            action.run();
        } finally {
            synchronized (c) {
                c.owner = null;
                c.buffer = null;
            }
        }
        return mine.toString(StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void write(int b) {
        if (owner == Thread.currentThread()) {
            buffer.write(b);
        } else {
            stderr.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        if (owner == Thread.currentThread()) {
            buffer.write(b, off, len);
        } else {
            stderr.write(b, off, len);
        }
    }

    @Override
    public synchronized void flush() {
        stderr.flush();
    }
}

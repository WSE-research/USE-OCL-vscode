package de.htwk.uselsp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Fängt ab, was USE nach System.out und System.err schreibt: während einer
 * Prüfung im prüfenden Thread in einen Puffer, sonst nach stderr. USE meldet
 * manche Warnungen nur über {@code org.tzi.use.util.Log}, und das schreibt nach
 * System.out, also mitten in den Protokollkanal.
 */
final class Capture extends OutputStream {

    private static Capture instance;

    private final PrintStream stderr;
    private Thread owner;
    private ByteArrayOutputStream buffer;

    private Capture(PrintStream stderr) {
        this.stderr = stderr;
    }

    /** Lenkt System.out und System.err um; vor der ersten USE-Klasse aufrufen. */
    static synchronized void install() {
        if (instance != null) {
            return;
        }
        instance = new Capture(System.err);
        PrintStream stream = new PrintStream(instance, true, StandardCharsets.UTF_8);
        System.setOut(stream);
        System.setErr(stream);
    }

    /** Führt {@code action} aus und liefert, was USE dabei ausgegeben hat. */
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

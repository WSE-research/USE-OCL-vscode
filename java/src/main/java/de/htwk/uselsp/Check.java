package de.htwk.uselsp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import de.htwk.uselsp.UseChecker.Message;

/**
 * Prüft Dateien wie der Sprachserver und gibt die Meldungen als
 * {@code datei:zeile:spalte: art: text} aus (Zeilen und Spalten ab 1); für Tests
 * und Skripte. Exit-Code 1, wenn es Fehler gibt.
 *
 * <p>Aufruf: {@code java -Duse.home=<USE-Ordner> -cp ... de.htwk.uselsp.Check datei.use|datei.soil ...}
 */
public final class Check {

    private Check() {
    }

    public static void main(String[] args) throws IOException {
        PrintStream out = System.out;
        Capture.install();
        UseChecker checker = new UseChecker(Main.useHome());
        boolean failed = false;
        for (String arg : args) {
            Path file = Path.of(arg);
            String text = Files.readString(file);
            List<Message> messages = arg.endsWith(".soil") ? checker.checkSoil(text)
                    : checker.checkModel(text, file.getFileName().toString(), file.toUri());
            for (Diagnostic d : Diagnostics.of(messages, text)) {
                out.printf("%s:%d:%d: %s: %s%n", arg, d.getRange().getStart().getLine() + 1,
                        d.getRange().getStart().getCharacter() + 1, d.getSeverity().name().toLowerCase(),
                        d.getMessage().replace("\n", " "));
                failed |= d.getSeverity() == DiagnosticSeverity.Error;
            }
        }
        out.flush();
        System.exit(failed ? 1 : 0);
    }
}

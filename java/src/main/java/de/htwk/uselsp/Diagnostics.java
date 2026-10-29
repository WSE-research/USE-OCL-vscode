package de.htwk.uselsp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import de.htwk.uselsp.UseChecker.Message;

/** Macht aus Meldungen von USE LSP-Diagnosen mit einem sinnvollen Bereich. */
final class Diagnostics {

    private static final String OPERATORS = "-<>=:.+*/|@#!";

    private Diagnostics() {
    }

    static List<Diagnostic> of(List<Message> messages, String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        List<Diagnostic> result = new ArrayList<>();
        for (Message m : messages) {
            result.add(new Diagnostic(range(lines, m), m.text(), severity(m.severity()), "USE"));
        }
        return result;
    }

    private static DiagnosticSeverity severity(UseChecker.Severity severity) {
        return switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFO -> DiagnosticSeverity.Information;
        };
    }

    /**
     * USE nennt nur den Anfang des Tokens. Der Bereich reicht über den Bezeichner,
     * die Zeichenkette oder den Operator dort; Meldungen ohne Position markieren die
     * erste Zeile, Meldungen am Dateiende das Ende der letzten nichtleeren Zeile.
     */
    static Range range(String[] lines, Message m) {
        if (m.line() <= 0) {
            return new Range(new Position(0, 0), new Position(0, lines[0].length()));
        }
        int line = Math.min(m.line() - 1, lines.length - 1);
        int column = m.column();
        if (column >= lines[line].length()) {
            while (line > 0 && lines[line].isBlank()) {
                line--;
            }
            int end = lines[line].stripTrailing().length();
            return new Range(new Position(line, Math.max(0, end - 1)), new Position(line, end));
        }
        return new Range(new Position(line, column), new Position(line, column + tokenLength(lines[line], column)));
    }

    private static int tokenLength(String line, int column) {
        char c = line.charAt(column);
        int end = column + 1;
        if (Character.isJavaIdentifierStart(c)) {
            while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
                end++;
            }
        } else if (c == '\'') {
            int close = line.indexOf('\'', column + 1);
            end = close < 0 ? line.length() : close + 1;
        } else if (OPERATORS.indexOf(c) >= 0) {
            while (end < line.length() && OPERATORS.indexOf(line.charAt(end)) >= 0) {
                end++;
            }
        }
        return end - column;
    }
}

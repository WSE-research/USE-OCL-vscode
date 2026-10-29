package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import de.htwk.uselsp.UseChecker.Message;
import de.htwk.uselsp.UseChecker.Severity;

class DiagnosticsTest {

    // columns in line 4:     0123456789012345
    private static final String TEXT = "class Medium\nattributes\n  titel : String\n  x = 'abc' -> y\n\n\n";
    private static final String[] LINES = TEXT.split("\n", -1);

    private static Range range(int line, int column) {
        return Diagnostics.range(LINES, new Message(line, column, "text", Severity.ERROR));
    }

    private static Range range(int startLine, int startColumn, int endLine, int endColumn) {
        return new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
    }

    @Test
    void identifierRangeCoversTheName() {
        assertEquals(range(0, 6, 0, 12), range(1, 6));
        assertEquals(range(2, 2, 2, 7), range(3, 2));
    }

    @Test
    void stringRangeCoversTheQuotes() {
        assertEquals(range(3, 6, 3, 11), range(4, 6));
    }

    @Test
    void unterminatedStringReachesTheEndOfTheLine() {
        assertEquals(range(0, 2, 0, 5), Diagnostics.range(new String[] {"x 'ab"}, new Message(1, 2, "", Severity.ERROR)));
    }

    @Test
    void operatorRangeCoversAllOperatorCharacters() {
        assertEquals(range(3, 12, 3, 14), range(4, 12));
        assertEquals(range(2, 8, 2, 9), range(3, 8));
    }

    @Test
    void otherCharactersGetOneColumn() {
        assertEquals(range(3, 3, 3, 4), range(4, 3));
    }

    @Test
    void messageWithoutPositionMarksTheFirstLine() {
        assertEquals(range(0, 0, 0, 12), range(0, 0));
    }

    @Test
    void messageAtTheEndMarksTheLastCharacterOfTheLastNonBlankLine() {
        assertEquals(range(3, 15, 3, 16), range(6, 0));
        assertEquals(range(3, 15, 3, 16), range(99, 0));
        assertEquals(range(3, 15, 3, 16), range(4, 40));
    }

    @Test
    void severitiesAndSourceAreMapped() {
        String text = "a\r\nb\rc\n";
        List<Diagnostic> diagnostics = Diagnostics.of(List.of(new Message(1, 0, "e", Severity.ERROR),
                new Message(2, 0, "w", Severity.WARNING), new Message(3, 0, "i", Severity.INFO)), text);
        assertEquals(List.of(DiagnosticSeverity.Error, DiagnosticSeverity.Warning, DiagnosticSeverity.Information),
                diagnostics.stream().map(Diagnostic::getSeverity).toList());
        assertEquals(List.of("e", "w", "i"), diagnostics.stream().map(Diagnostic::getMessage).toList());
        assertEquals(List.of(0, 1, 2), diagnostics.stream().map(d -> d.getRange().getStart().getLine()).toList());
        diagnostics.forEach(d -> assertEquals("USE", d.getSource()));
    }
}

package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.tzi.use.uml.mm.MModel;

import de.htwk.uselsp.UseChecker.Checked;
import de.htwk.uselsp.UseChecker.Message;
import de.htwk.uselsp.UseChecker.Severity;

class UseCheckerTest {

    private final UseChecker checker = TestSupport.checker();

    /** Expected message: line from 1, column from 0, and a part of the text. */
    private record Expected(int line, int column, Severity severity, String text) {
    }

    private static void assertMessages(List<Expected> expected, List<Message> actual) {
        List<Message> sorted = actual.stream().sorted(Comparator.comparingInt(Message::line)
                .thenComparingInt(Message::column)).toList();
        assertEquals(expected.size(), sorted.size(), () -> "messages: " + sorted);
        for (int i = 0; i < expected.size(); i++) {
            Expected e = expected.get(i);
            Message m = sorted.get(i);
            assertEquals(e.line(), m.line(), () -> "line of " + m);
            assertEquals(e.column(), m.column(), () -> "column of " + m);
            assertEquals(e.severity(), m.severity(), () -> "severity of " + m);
            assertTrue(m.text().contains(e.text()), () -> "text of " + m);
        }
    }

    private Checked checkModel(String name) {
        return checker.checkModel(TestSupport.sample(name), name, TestSupport.SAMPLES.resolve(name).toUri());
    }

    @Test
    void validModelHasNoMessagesAndYieldsTheModel() {
        Checked checked = checkModel("bibliothek.use");
        assertEquals(List.of(), checked.messages());
        assertNotNull(checked.model());
        assertEquals("Bibliothek", checked.model().name());
        assertNotNull(checked.model().getClass("Medium"));
        assertNotNull(checked.model().getAssociation("Leiht"));
    }

    @Test
    void typeErrorsAndWarningsAreLocated() {
        Checked checked = checkModel("bibliothek-fehler.use");
        assertMessages(List.of(
                new Expected(8, 13, Severity.ERROR, "Expected type name, found `Boolen'"),
                new Expected(31, 7, Severity.ERROR, "Undefined operation named `medien'"),
                new Expected(34, 13, Severity.WARNING, "will always evaluate to false"),
                new Expected(37, 22, Severity.ERROR, "Undefined operation `Integer.+(Boolean)")),
                checked.messages());
        assertNull(checked.model());
    }

    @Test
    void syntaxErrorsAreLocated() {
        assertMessages(List.of(new Expected(14, 5, Severity.ERROR, "no viable alternative at input 'Medium'")),
                checkModel("bibliothek-syntax.use").messages());
    }

    @Test
    void modelWithoutBaseUriIsChecked() {
        String text = TestSupport.sample("bibliothek-syntax.use");
        assertMessages(List.of(new Expected(14, 5, Severity.ERROR, "no viable alternative")),
                checker.checkModel(text, "model.use", null).messages());
    }

    @Test
    void onlyAWarningKeepsTheModel() {
        String text = "model M\nclass A\nattributes\n  n : Integer\nend\nconstraints\n"
                + "context A inv W:\n  Set{1, 2}->includes('x')\n";
        Checked checked = checker.checkModel(text, "w.use", null);
        assertMessages(List.of(new Expected(8, 13, Severity.WARNING, "will always evaluate to false")),
                checked.messages());
        assertNotNull(checked.model());
    }

    @Test
    void redefinitionIsAnErrorWithAnInformationAboutTheFirstDeclaration() {
        String text = "model M\nclass A\nend\nclass A\nend\n";
        assertMessages(List.of(new Expected(2, 6, Severity.INFO, "`A' previously declared here"),
                new Expected(4, 6, Severity.ERROR, "Redefinition of `A'")),
                checker.checkModel(text, "r.use", null).messages());
    }

    @Test
    void compileReturnsTheModelOrNull() {
        MModel model = TestSupport.model("bibliothek.use");
        assertNotNull(model);
        assertNotNull(model.getClass("Mitglied").operation("ausleihen", true));
        assertNull(TestSupport.model("bibliothek-fehler.use"));
        assertNull(checker.compile("model", "x.use", null));
    }

    @Test
    void validScriptHasNoMessages() {
        assertEquals(List.of(), checker.checkSoil(TestSupport.sample("bibliothek.soil")));
    }

    @Test
    void scriptErrorsAreLocatedWithHints() {
        assertMessages(List.of(
                new Expected(1, 0, Severity.ERROR, "Soil-Anweisungen beginnen mit `!'"),
                new Expected(2, 10, Severity.ERROR, "In Soil wird mit `:=' zugewiesen"),
                new Expected(3, 8, Severity.ERROR, "no viable alternative at input '('"),
                // at the end of the line; Diagnostics moves it onto the last character
                new Expected(4, 22, Severity.ERROR, "'<EOF>'"),
                new Expected(5, 0, Severity.ERROR, "Statement expected."),
                new Expected(6, 0, Severity.ERROR, "Unknown command `hilfe'")),
                checker.checkSoil(TestSupport.sample("bibliothek-fehler.soil")));
    }

    @Test
    void shellCommandsCommentsAndBlankLinesAreAccepted() {
        String script = "check\ninfo class Medium\nhelp\nq\nopen bibliothek.use\nreset\n-- note\n// note\n\n  \n";
        assertEquals(List.of(), checker.checkSoil(script));
    }

    @Test
    void unknownCommandWithoutSoilShapeGetsNoHint() {
        List<Message> messages = checker.checkSoil("foo bar");
        assertEquals(1, messages.size());
        assertEquals("Unknown command `foo bar'. Try `help'.", messages.get(0).text());
    }

    @Test
    void queriesReportOnlySyntaxErrors() {
        // against the empty model every name is unknown, so only syntax counts
        assertEquals(List.of(), checker.checkSoil("? ada.medium->size()\n?? m1.titel\n: 1 + 2"));
        List<Message> messages = checker.checkSoil("\n  ? 1 +");
        assertEquals(1, messages.size());
        assertEquals(2, messages.get(0).line());
        assertTrue(messages.get(0).column() >= 4, () -> "column: " + messages.get(0));
    }

    @Test
    void statementsWithDoubleBangAndLegacyFormsAreParsed() {
        assertEquals(List.of(), checker.checkSoil("!!new Medium('m2')\n!create x, y : Medium\n!openter ada ausleihen(m1)\n"
                + "!opexit\n!set m1.titel := 'Dune'"));
        assertMessages(List.of(new Expected(1, 0, Severity.ERROR, "Statement expected.")), checker.checkSoil("!!   "));
    }

    @Test
    void comparisonInsteadOfAssignmentIsExplained() {
        assertMessages(List.of(new Expected(1, 10, Severity.ERROR, "In Soil wird mit `:=' zugewiesen")),
                checker.checkSoil("!m1.titel = 'Dune'"));
        // = inside an operation call or as part of >= is no assignment
        assertEquals(List.of(), checker.checkSoil("!ada.ausleihen(m1)"));
    }
}

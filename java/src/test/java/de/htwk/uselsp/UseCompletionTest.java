package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.tzi.use.uml.mm.MModel;

import de.htwk.uselsp.UseCompletion.Model;

/** Completion at positions marked with § in text appended to the samples bibliothek.use and bibliothek.soil. */
class UseCompletionTest {

    private static MModel bibliothek;
    private static String modelText;
    private static String scriptText;

    private final UseCompletion completion = new UseCompletion();

    @BeforeAll
    static void compile() {
        modelText = TestSupport.sample("bibliothek.use");
        scriptText = TestSupport.sample("bibliothek.soil");
        bibliothek = TestSupport.model("bibliothek.use");
        assertNotNull(bibliothek);
    }

    private List<CompletionItem> complete(String text, boolean soil, String trigger, List<Model> models) {
        int cursor = text.indexOf('§');
        String before = text.substring(0, cursor);
        int line = (int) before.chars().filter(c -> c == '\n').count();
        int character = cursor - before.lastIndexOf('\n') - 1;
        return completion.complete(text.replace("§", ""), line, character, soil, trigger, models);
    }

    /** Completion in bibliothek.use with {@code appended} at its end; the model is the sample's last good version. */
    private List<CompletionItem> inModel(String appended, String trigger) {
        return complete(modelText + "\n" + appended, false, trigger, List.of(new Model(bibliothek, modelText)));
    }

    private List<CompletionItem> inScript(String appended, String trigger) {
        return complete(scriptText + appended, true, trigger, List.of(new Model(bibliothek, modelText)));
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    private static CompletionItem item(List<CompletionItem> items, String label) {
        return items.stream().filter(i -> i.getLabel().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError(label + " missing in " + labels(items)));
    }

    private static void assertOffers(List<CompletionItem> items, List<String> wanted, List<String> unwanted) {
        List<String> labels = labels(items);
        for (String w : wanted) {
            assertTrue(labels.contains(w), () -> w + " missing in " + labels);
        }
        for (String u : unwanted) {
            assertFalse(labels.contains(u), () -> u + " unexpected in " + labels);
        }
    }

    @Nested
    class Models {

        @Test
        void dotOffersAttributesRolesAndStandardOperations() {
            List<CompletionItem> items = inModel("context Medium inv T:\n  self.§", ".");
            assertOffers(items, List.of("titel", "zustand", "leser", "oclIsKindOf", "oclIsUndefined"),
                    List.of("ausweis", "medium", "select"));
            CompletionItem titel = item(items, "titel");
            assertEquals(CompletionItemKind.Field, titel.getKind());
            assertEquals("Attribut von Medium", titel.getDetail());
            assertEquals("String", titel.getLabelDetails().getDescription());
            CompletionItem leser = item(items, "leser");
            assertEquals(CompletionItemKind.Property, leser.getKind());
            assertEquals("Rolle in Leiht", leser.getDetail());
            assertEquals("Mitglied", leser.getLabelDetails().getDescription());
        }

        @Test
        void arrowOffersCollectionOperationsAndIteratorTemplates() {
            List<CompletionItem> items = inModel("context Mitglied inv T:\n  self.medium->§", ">");
            assertOffers(items, List.of("notEmpty", "includes", "size", "select", "forAll", "iterate", "selectByKind"),
                    List.of("first", "titel"));
            CompletionItem select = item(items, "select");
            assertEquals(InsertTextFormat.Snippet, select.getInsertTextFormat());
            assertEquals("select(${1:m} | $0)", select.getInsertText());
        }

        @Test
        void arrowOnAnObjectTreatsItAsASet() {
            assertOffers(inModel("context Medium inv T:\n  self.leser->§", ">"), List.of("isEmpty", "forAll"),
                    List.of());
        }

        @Test
        void doubleColonOffersEnumerationLiterals() {
            List<CompletionItem> items = inModel("context Medium inv T:\n  self.zustand = Zustand::§", ":");
            assertOffers(items, List.of("verfuegbar", "ausgeliehen", "gesperrt"), List.of("Medium"));
            assertEquals(CompletionItemKind.EnumMember, item(items, "gesperrt").getKind());
        }

        @Test
        void iteratorVariablesAreTyped() {
            assertOffers(inModel("context Mitglied inv T:\n  self.medium->forAll(x | x.§", "."),
                    List.of("titel", "leser"), List.of("ausweis"));
            assertOffers(inModel("context Mitglied inv T:\n  self.medium->exists(x : Medium | x.§", "."),
                    List.of("titel"), List.of());
            assertOffers(inModel("context Mitglied inv T:\n  self.medium->select(titel = 'x')->collect(m | m.§", "."),
                    List.of("zustand"), List.of());
        }

        @Test
        void letVariablesAreTyped() {
            assertOffers(inModel("context Mitglied inv T:\n  let ms = self.medium in ms->§", ">"),
                    List.of("size"), List.of());
            assertOffers(inModel("context Mitglied inv T:\n  let n : Integer = 1 in §", null), List.of("n"),
                    List.of());
        }

        @Test
        void typingANameOffersVariablesFeaturesOfSelfClassesAndKeywords() {
            List<CompletionItem> items = inModel("context Mitglied inv T:\n  medi§", null);
            assertOffers(items, List.of("self", "medium", "ausweis", "Medium", "Zustand", "implies", "if"),
                    List.of("titel"));
            assertEquals(CompletionItemKind.Variable, item(items, "self").getKind());
        }

        @Test
        void parametersAndResultAreVariablesInPostconditions() {
            assertOffers(inModel("context Mitglied::ausleihen(m : Medium)\n  post P: self.medium->includes(m) and m.§",
                    "."), List.of("titel"), List.of());
            assertOffers(inModel("context Mitglied::anzahl() : Integer\n  post R: §", null),
                    List.of("result", "self"), List.of());
            assertOffers(inModel("context Mitglied::anzahl() : Integer\n  pre R: §", null), List.of("self"),
                    List.of("result"));
        }

        @Test
        void atPreKeepsTheType() {
            assertOffers(inModel("context Mitglied::ausleihen(m : Medium)\n  post P: self.medium@pre->§", ">"),
                    List.of("including", "size"), List.of());
        }

        @Test
        void classNameOffersAllInstances() {
            CompletionItem all = item(inModel("context Mitglied inv T:\n  Medium.§", "."), "allInstances");
            assertEquals("allInstances()", all.getInsertText());
        }

        @Test
        void typePositionsOfferTypes() {
            assertOffers(inModel("context Mitglied inv T:\n  self.medium->select(m : §", null),
                    List.of("Integer", "Medium", "Zustand", "Set()"), List.of("self"));
            assertOffers(inModel("class Buch\nattributes\n  seiten : §", null), List.of("Integer", "Zustand"),
                    List.of("attributes"));
        }

        @Test
        void contextAndSuperclassOfferClasses() {
            assertOffers(inModel("context §", null), List.of("Medium", "Mitglied"), List.of("Zustand", "Leiht"));
            assertOffers(inModel("class Buch < §", null), List.of("Medium"), List.of());
        }

        @Test
        void contextWithDoubleColonOffersOperations() {
            CompletionItem anzahl = item(inModel("context Mitglied::§", ":"), "anzahl");
            assertEquals("anzahl() : Integer", anzahl.getInsertText());
        }

        @Test
        void topLevelOffersDeclarationTemplates() {
            // not after the sample: a new line there may still continue its last postcondition
            List<CompletionItem> items = complete("model Neu\n\n§", false, null, List.of(new Model(bibliothek, modelText)));
            assertOffers(items, List.of("class", "abstract class", "association", "composition", "enum", "context"),
                    List.of("self", "attributes"));
            assertEquals(InsertTextFormat.Snippet, item(items, "class").getInsertTextFormat());
        }

        @Test
        void classAndAssociationBodiesOfferTheirKeywords() {
            assertOffers(inModel("class Buch\n§", null), List.of("attributes", "operations", "end"),
                    List.of("class"));
            assertOffers(inModel("class Buch\noperations\n  f() : Integer\n§", null), List.of("pre", "post"),
                    List.of());
            assertOffers(inModel("association A between\n  Medium [*] role x\n§", null), List.of("role", "end"),
                    List.of("attributes"));
            assertOffers(inModel("class Buch\nattributes\n  seiten : Integer §", null), List.of("init", "derive"),
                    List.of());
        }

        @Test
        void triggersThatDoNotFitGiveNothing() {
            assertEquals(List.of(), inModel("context Mitglied inv T:\n  self.medium->size() >§", ">"));
            assertEquals(List.of(), inModel("context Mitglied inv T:\n  self.medium->size() = 1:§", ":"));
            assertEquals(List.of(), inModel("context Mitglied inv T:\n  1.§", "."));
        }

        @Test
        void commentsAndStringsGiveNothing() {
            assertEquals(List.of(), inModel("context Mitglied inv T:\n  self.anzahl() >= 0 -- self.§", "."));
            assertEquals(List.of(), inModel("context Mitglied inv T:\n  self.ausweis = 'a.§", "."));
            assertEquals(List.of(), inModel("/* self.§", "."));
        }

        @Test
        void withoutAModelOnlyKeywordsAndNamesFromTheTextRemain() {
            List<CompletionItem> items = complete(modelText + "\ncontext Mitglied inv T:\n  §", false, null,
                    List.of(new Model(null, modelText)));
            assertOffers(items, List.of("self", "implies", "Medium"), List.of("ausweis"));
            assertEquals(List.of(), complete(modelText + "\ncontext Mitglied inv T:\n  self.§", false, ".",
                    List.of(new Model(null, modelText))));
            assertOffers(complete("§", false, null, List.of()), List.of("class"), List.of());
        }
    }

    @Nested
    class Scripts {

        @Test
        void dotOffersAllOperationsInStatements() {
            assertOffers(inScript("!ada.§", "."), List.of("ausweis", "medium", "anzahl", "ausleihen"),
                    List.of("titel"));
            CompletionItem ausleihen = item(inScript("!ada.§", "."), "ausleihen");
            assertEquals("ausleihen(${1:m})", ausleihen.getInsertText());
        }

        @Test
        void queriesOfferOnlyOperationsWithResult() {
            assertOffers(inScript("? ada.§", "."), List.of("ausweis", "anzahl"), List.of("ausleihen"));
            assertOffers(inScript("? ada.medium->select(x | x.§", "."), List.of("titel", "zustand"), List.of());
        }

        @Test
        void lineStartOffersCommandsAndStatements() {
            List<CompletionItem> items = inScript("che§", null);
            assertOffers(items, List.of("check", "info class", "!new", "!insert"), List.of());
            CompletionItem bangNew = item(items, "!new");
            assertEquals("new", bangNew.getFilterText());
            assertEquals("!new ", bangNew.getInsertText());
            assertEquals("editor.action.triggerSuggest", bangNew.getCommand().getCommand());
            assertNull(item(items, "!opexit").getCommand());
        }

        @Test
        void bangOffersStatementsAndObjects() {
            assertOffers(inScript("!§", "!"), List.of("new", "destroy", "insert", "create", "opexit", "ada", "m1"),
                    List.of("check"));
            assertEquals(List.of(), inScript("? 1 !§", "!"));
        }

        @Test
        void newOffersClassesOnly() {
            assertOffers(inScript("!new §", null), List.of("Mitglied", "Medium"), List.of("ada", "Zustand"));
            assertOffers(inScript("!x := new §", null), List.of("Medium"), List.of());
            assertOffers(inScript("!create b : §", null), List.of("Medium"), List.of());
        }

        @Test
        void linkStatementsOfferObjectsAndAssociations() {
            assertOffers(inScript("!insert (§", null), List.of("ada", "m1"), List.of("Leiht"));
            assertOffers(inScript("!insert (ada, m1) into §", null), List.of("Leiht"), List.of("ada"));
            assertOffers(inScript("!delete (ada, m1) from §", null), List.of("Leiht"), List.of());
        }

        @Test
        void destroySetAndOpenterOfferObjects() {
            assertOffers(inScript("!destroy §", null), List.of("ada", "m1"), List.of("Mitglied"));
            assertOffers(inScript("!set §", null), List.of("ada"), List.of());
            assertOffers(inScript("!openter ada §", null), List.of("ausleihen", "anzahl"), List.of("titel"));
        }

        @Test
        void objectsFromCreateAssignmentAndDeclareAreKnown() {
            assertOffers(inScript("!create b1, b2 : Medium\n!b2.§", "."), List.of("titel"), List.of("ausweis"));
            assertOffers(inScript("!b := new Medium('b')\n!b.§", "."), List.of("zustand"), List.of());
            assertOffers(inScript("!declare k : Mitglied\n!k.§", "."), List.of("ausweis"), List.of());
        }

        @Test
        void expressionsOfferObjectsAndKeywords() {
            assertOffers(inScript("? §", null), List.of("ada", "m1", "Medium", "not"), List.of("check"));
            assertOffers(inScript("!m1.zustand := Zustand::§", ":"), List.of("verfuegbar"), List.of());
            assertOffers(inScript("!declare n : §", null), List.of("Integer", "Medium"), List.of());
        }

        @Test
        void withoutModelsStatementsRemain() {
            List<CompletionItem> items = complete(scriptText + "§", true, null, List.of());
            assertOffers(items, List.of("check", "!new"), List.of());
            assertEquals(List.of(), complete(scriptText + "!ada.§", true, ".", List.of()));
        }
    }

    @Nested
    class Text {

        @Test
        void blankReplacesCommentsAndStringsButKeepsLines() {
            assertEquals("a           \nb '   ' c     ", UseCompletion.blank("a -- comment\nb 'str' c /**/"));
            assertEquals("x" + " ".repeat(9) + "y", UseCompletion.blank("x /* a */ y"));
        }

        @Test
        void blankIsNullInsideACommentOrString() {
            assertNull(UseCompletion.blank("a -- open"));
            assertNull(UseCompletion.blank("a // open"));
            assertNull(UseCompletion.blank("a /* open"));
            assertNull(UseCompletion.blank("a 'open"));
            assertEquals("'  '", UseCompletion.blank("'\\''"));
        }

        @Test
        void chainStartFindsTheReceiver() {
            assertChain("self.medium->select(m | m.titel = 'x')", "x = self.medium->select(m | m.titel = 'x')");
            assertChain("Zustand", "self.zustand = Zustand");
            assertChain("m1.medium@pre", "post: m1.medium@pre");
            assertChain("self\n    .medium", "inv: self\n    .medium");
            assertChain("Set{1, 2}", "inv: Set{1, 2}");
            assertChain("a[1]", "b and a[1]");
            assertChain("e", "(d) e");
        }

        private static void assertChain(String expected, String text) {
            assertEquals(expected, text.substring(UseCompletion.chainStart(text, text.length(), 0)));
        }

        @Test
        void chainStartStopsAtTheLowerBoundAndAtUnbalancedBrackets() {
            String text = "!ada.medium";
            assertEquals("ada.medium", text.substring(UseCompletion.chainStart(text, text.length(), 1)));
            String open = "f(a, b";
            assertEquals("b", open.substring(UseCompletion.chainStart(open, open.length(), 0)));
            // a closing bracket without its opening one ends the search: no receiver
            String unbalanced = "x)";
            assertEquals("", unbalanced.substring(UseCompletion.chainStart(unbalanced, unbalanced.length(), 0)));
        }
    }
}

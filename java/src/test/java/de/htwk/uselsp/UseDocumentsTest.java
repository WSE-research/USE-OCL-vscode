package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

class UseDocumentsTest {

    private static final String MODEL_URI = TestSupport.SAMPLES.resolve("bibliothek.use").toUri().toString();
    private static final String SCRIPT_URI = TestSupport.SAMPLES.resolve("bibliothek.soil").toUri().toString();

    /** A client that records diagnostics and answers useOcl/models with {@link #models}. */
    static class Client implements UseClient {
        final BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
        final AtomicInteger modelRequests = new AtomicInteger();
        CompletableFuture<List<ModelFile>> models = CompletableFuture.completedFuture(List.of());

        @Override
        public CompletableFuture<List<ModelFile>> models(ModelsParams params) {
            modelRequests.incrementAndGet();
            return models;
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.add(params);
        }

        PublishDiagnosticsParams next() throws InterruptedException {
            PublishDiagnosticsParams p = diagnostics.poll(30, TimeUnit.SECONDS);
            assertNotNull(p, "no diagnostics within 30 s");
            return p;
        }

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void showMessage(MessageParams params) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
        }
    }

    private final Client client = new Client();
    private UseDocuments documents;

    @BeforeEach
    void start() {
        documents = new UseDocuments(TestSupport.checker());
        documents.connect(client);
    }

    @AfterEach
    void stop() {
        documents.shutdown();
    }

    private void open(String uri, String language, String text) {
        documents.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, language, 1, text)));
    }

    private void change(String uri, int version, String text) {
        documents.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(text))));
    }

    /** Completion at the end of {@code text}. */
    private List<CompletionItem> complete(String uri, String text, String trigger) throws Exception {
        String[] lines = text.split("\n", -1);
        CompletionParams params = new CompletionParams(new TextDocumentIdentifier(uri),
                new Position(lines.length - 1, lines[lines.length - 1].length()));
        if (trigger != null) {
            params.setContext(new CompletionContext(CompletionTriggerKind.TriggerCharacter, trigger));
        }
        return documents.completion(params).get(30, TimeUnit.SECONDS).getLeft();
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    @Test
    void openingAFaultyModelPublishesItsDiagnostics() throws Exception {
        String uri = TestSupport.SAMPLES.resolve("bibliothek-fehler.use").toUri().toString();
        open(uri, "use", TestSupport.sample("bibliothek-fehler.use"));
        PublishDiagnosticsParams p = client.next();
        assertEquals(uri, p.getUri());
        assertEquals(1, p.getVersion());
        assertEquals(4, p.getDiagnostics().size());
        assertEquals(1, p.getDiagnostics().stream().filter(d -> d.getSeverity() == DiagnosticSeverity.Warning).count());
    }

    @Test
    void changesAreCheckedAgainAndClosingClearsTheDiagnostics() throws Exception {
        String text = TestSupport.sample("bibliothek.use");
        open(MODEL_URI, "use", text);
        assertEquals(List.of(), client.next().getDiagnostics());

        change(MODEL_URI, 2, text.replace("self.leser->notEmpty()", "self.leserin->notEmpty()"));
        PublishDiagnosticsParams p = client.next();
        assertEquals(2, p.getVersion());
        assertEquals(1, p.getDiagnostics().size());
        assertTrue(p.getDiagnostics().get(0).getMessage().contains("leserin"));

        documents.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(MODEL_URI)));
        documents.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(MODEL_URI)));
        p = client.next();
        assertEquals(List.of(), p.getDiagnostics());
        assertNull(p.getVersion());
    }

    @Test
    void scriptsAreRecognizedByLanguageOrExtension() throws Exception {
        // a client may leave out the language; LSP4J's constructor rejects that, its JSON reader does not
        TextDocumentItem item = new Gson().fromJson("{\"uri\": \"" + SCRIPT_URI + "\", \"version\": 1, \"text\": \"hilfe\"}",
                TextDocumentItem.class);
        documents.didOpen(new DidOpenTextDocumentParams(item));
        assertEquals(1, client.next().getDiagnostics().size());
        open("untitled:Untitled-1", "soil", "!new Medium('m')\n");
        assertEquals(List.of(), client.next().getDiagnostics());
    }

    @Test
    void unsavedModelsAreChecked() throws Exception {
        open("untitled:Untitled-2", "use", "model M\nclass A\nattributes\n  n : Integr\nend\n");
        PublishDiagnosticsParams p = client.next();
        assertEquals(1, p.getDiagnostics().size());
        assertEquals(3, p.getDiagnostics().get(0).getRange().getStart().getLine());
    }

    @Test
    void changesOfUnknownDocumentsAreIgnored() throws Exception {
        change("file:///nicht/offen.use", 2, "model X");
        assertNull(client.diagnostics.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void modelCompletionUsesTheLastErrorFreeVersion() throws Exception {
        String text = TestSupport.sample("bibliothek.use");
        open(MODEL_URI, "use", text);
        client.next();
        // typing: the text with the unfinished expression has errors
        String typing = text + "\ncontext Medium inv T:\n  self.";
        change(MODEL_URI, 2, typing);
        List<String> labels = labels(complete(MODEL_URI, typing, "."));
        assertTrue(labels.contains("titel") && labels.contains("leser"), () -> "labels: " + labels);
        assertEquals(0, client.modelRequests.get());
    }

    @Test
    void scriptCompletionAsksTheClientForTheModels() throws Exception {
        client.models = CompletableFuture.completedFuture(
                List.of(new UseClient.ModelFile(MODEL_URI, TestSupport.sample("bibliothek.use"))));
        String script = TestSupport.sample("bibliothek.soil") + "!ada.";
        open(SCRIPT_URI, "soil", script);
        List<String> labels = labels(complete(SCRIPT_URI, script, "."));
        assertTrue(labels.contains("ausweis") && labels.contains("ausleihen"), () -> "labels: " + labels);
        assertEquals(1, client.modelRequests.get());
    }

    @Test
    void scriptCompletionWithoutAnswerFromTheClientHasNoModels() throws Exception {
        client.models = CompletableFuture.failedFuture(new IllegalStateException("no answer"));
        String script = "!ada.";
        open(SCRIPT_URI, "soil", script);
        assertEquals(List.of(), complete(SCRIPT_URI, script, "."));
        client.models = CompletableFuture.completedFuture(null);
        assertEquals(List.of(), complete(SCRIPT_URI, script, "."));
        assertTrue(labels(complete(SCRIPT_URI, "", null)).contains("check"));
    }

    @Test
    void scriptCompletionWithAPlainLanguageClientHasNoModels() throws Exception {
        UseDocuments plain = new UseDocuments(TestSupport.checker());
        plain.connect(new org.eclipse.lsp4j.services.LanguageClient() {
            @Override
            public void telemetryEvent(Object object) {
            }

            @Override
            public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            }

            @Override
            public void showMessage(MessageParams messageParams) {
            }

            @Override
            public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void logMessage(MessageParams message) {
            }
        });
        try {
            plain.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(SCRIPT_URI, "soil", 1, "!ada.")));
            CompletionParams params = new CompletionParams(new TextDocumentIdentifier(SCRIPT_URI), new Position(0, 5));
            assertEquals(List.of(), plain.completion(params).get(30, TimeUnit.SECONDS).getLeft());
        } finally {
            plain.shutdown();
        }
    }

    @Test
    void completionOfUnknownDocumentsIsEmpty() throws Exception {
        assertEquals(List.of(), complete("file:///nicht/offen.use", "self.", "."));
    }
}

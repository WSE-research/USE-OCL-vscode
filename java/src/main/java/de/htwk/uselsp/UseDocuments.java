package de.htwk.uselsp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import de.htwk.uselsp.UseChecker.Message;
import de.htwk.uselsp.UseChecker.Severity;

/**
 * Hält die offenen Dokumente und prüft sie nach jeder Änderung (kurz verzögert,
 * damit nicht jeder Tastendruck einen Lauf auslöst) in einem einzigen Thread.
 */
final class UseDocuments implements TextDocumentService {

    private static final long DELAY_MS = 250;

    private record Document(String language, String text, int version) {
    }

    private final UseChecker checker;
    private final Map<String, Document> open = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "use-check");
        t.setDaemon(true);
        return t;
    });
    private volatile LanguageClient client;

    UseDocuments(UseChecker checker) {
        this.checker = checker;
    }

    void connect(LanguageClient client) {
        this.client = client;
    }

    void shutdown() {
        worker.shutdownNow();
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem item = params.getTextDocument();
        open.put(item.getUri(), new Document(item.getLanguageId(), item.getText(), item.getVersion()));
        schedule(item.getUri(), 0);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        Document old = open.get(uri);
        if (old == null || params.getContentChanges().isEmpty()) {
            return;
        }
        // vollständige Synchronisation: die letzte Änderung enthält den ganzen Text
        String text = params.getContentChanges().get(params.getContentChanges().size() - 1).getText();
        open.put(uri, new Document(old.language(), text, params.getTextDocument().getVersion()));
        schedule(uri, DELAY_MS);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        open.remove(uri);
        ScheduledFuture<?> job = pending.remove(uri);
        if (job != null) {
            job.cancel(false);
        }
        LanguageClient c = client;
        if (c != null) {
            c.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // der Text ist schon aus didChange bekannt
    }

    private void schedule(String uri, long delayMs) {
        ScheduledFuture<?> job = worker.schedule(() -> check(uri), delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> old = pending.put(uri, job);
        if (old != null) {
            old.cancel(false);
        }
    }

    private void check(String uri) {
        Document doc = open.get(uri);
        LanguageClient c = client;
        if (doc == null || c == null) {
            return;
        }
        List<Message> messages;
        try {
            messages = isSoil(uri, doc) ? checker.checkSoil(doc.text())
                    : checker.checkModel(doc.text(), fileName(uri), baseUri(uri));
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            // ein Fehler in USE soll nur diese Prüfung kosten, nicht den Server
            e.printStackTrace();
            messages = List.of(new Message(0, 0, "Interner Fehler beim Prüfen: " + e, Severity.ERROR));
        }
        c.publishDiagnostics(new PublishDiagnosticsParams(uri, Diagnostics.of(messages, doc.text()), doc.version()));
    }

    private static boolean isSoil(String uri, Document doc) {
        return "soil".equals(doc.language()) || doc.language() == null && uri.endsWith(".soil");
    }

    /** Der Dateiname, unter dem USE die Meldungen ausgibt. */
    private static String fileName(String uri) {
        try {
            String path = URI.create(uri).getPath();
            if (path != null && !path.isEmpty()) {
                return path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (IllegalArgumentException e) {
            // kein gültiger URI, etwa bei ungespeicherten Dokumenten
        }
        return "model.use";
    }

    /** Basis für {@code import}; nur für Dateien, die der Server auch lesen kann. */
    private static URI baseUri(String uri) {
        try {
            URI u = URI.create(uri);
            return "file".equals(u.getScheme()) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

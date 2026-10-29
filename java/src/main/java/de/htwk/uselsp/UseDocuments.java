package de.htwk.uselsp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.tzi.use.uml.mm.MModel;

import de.htwk.uselsp.UseChecker.Checked;
import de.htwk.uselsp.UseChecker.Message;
import de.htwk.uselsp.UseChecker.Severity;
import de.htwk.uselsp.UseClient.ModelFile;
import de.htwk.uselsp.UseClient.ModelsParams;

/**
 * Holds the open documents and checks them after every change (with a short delay,
 * so that not every keystroke triggers a run) in a single thread; the auto-completion
 * suggestions are produced in the same thread.
 */
final class UseDocuments implements TextDocumentService {

    private static final long DELAY_MS = 250;

    private record Document(String language, String text, int version) {
    }

    /** Last compiled text of a model and its last error-free version (or null). */
    private record Compiled(String text, MModel model) {
    }

    private final UseChecker checker;
    private final UseCompletion completion = new UseCompletion();
    private final Map<String, Document> open = new ConcurrentHashMap<>();
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();
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
        // full synchronization: the last change contains the whole text
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
        // the text is already known from didChange
    }

    /**
     * Suggestions at the cursor. A model is completed from its last error-free
     * version (while typing, the text usually contains errors), a script from the
     * models in its folder, which the client sends.
     */
    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        String uri = params.getTextDocument().getUri();
        Document doc = open.get(uri);
        if (doc == null) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        boolean soil = isSoil(uri, doc);
        String trigger = params.getContext() == null ? null : params.getContext().getTriggerCharacter();
        CompletableFuture<List<ModelFile>> files = soil ? modelsNextTo(uri)
                : CompletableFuture.completedFuture(List.of(new ModelFile(uri, doc.text())));
        return files.thenApplyAsync(list -> {
            List<UseCompletion.Model> models = new ArrayList<>();
            for (ModelFile f : list) {
                models.add(new UseCompletion.Model(model(f.uri(), f.text()), f.text()));
            }
            try {
                return Either.forLeft(completion.complete(doc.text(), params.getPosition().getLine(),
                        params.getPosition().getCharacter(), soil, trigger, models));
            } catch (RuntimeException | StackOverflowError | LinkageError e) {
                e.printStackTrace();
                return Either.forLeft(List.of());
            }
        }, worker);
    }

    /** The models in the script's folder, from the client; none if there is no answer. */
    private CompletableFuture<List<ModelFile>> modelsNextTo(String uri) {
        if (!(client instanceof UseClient c)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return c.models(new ModelsParams(uri)).orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(e -> List.of())
                .thenApply(list -> list == null ? List.of() : list);
    }

    /** The model for a text; if the text has errors, its last error-free version. Check thread only. */
    private MModel model(String uri, String text) {
        Compiled last = compiled.get(uri);
        if (last != null && last.text().equals(text)) {
            return last.model();
        }
        MModel model;
        try {
            model = checker.compile(text, fileName(uri), baseUri(uri));
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            model = null;
        }
        remember(uri, text, model);
        return compiled.get(uri).model();
    }

    private void remember(String uri, String text, MModel model) {
        Compiled last = compiled.get(uri);
        compiled.put(uri, new Compiled(text, model != null || last == null ? model : last.model()));
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
            if (isSoil(uri, doc)) {
                messages = checker.checkSoil(doc.text());
            } else {
                Checked checked = checker.checkModel(doc.text(), fileName(uri), baseUri(uri));
                remember(uri, doc.text(), checked.model());
                messages = checked.messages();
            }
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            // an error in USE should cost only this check, not the server
            e.printStackTrace();
            messages = List.of(new Message(0, 0, "Interner Fehler beim Prüfen: " + e, Severity.ERROR));
        }
        c.publishDiagnostics(new PublishDiagnosticsParams(uri, Diagnostics.of(messages, doc.text()), doc.version()));
    }

    private static boolean isSoil(String uri, Document doc) {
        return "soil".equals(doc.language()) || doc.language() == null && uri.endsWith(".soil");
    }

    /** The file name under which USE prints the messages. */
    private static String fileName(String uri) {
        try {
            String path = URI.create(uri).getPath();
            if (path != null && !path.isEmpty()) {
                return path.substring(path.lastIndexOf('/') + 1);
            }
        } catch (IllegalArgumentException e) {
            // not a valid URI, e.g. for unsaved documents
        }
        return "model.use";
    }

    /** Base for {@code import}; only for files the server can actually read. */
    private static URI baseUri(String uri) {
        try {
            URI u = URI.create(uri);
            return "file".equals(u.getScheme()) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

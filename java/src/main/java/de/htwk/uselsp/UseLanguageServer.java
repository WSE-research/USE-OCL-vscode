package de.htwk.uselsp;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** Lebenszyklus des Servers; die eigentliche Arbeit macht {@link UseDocuments}. */
final class UseLanguageServer implements LanguageServer, LanguageClientAware {

    static final String VERSION = "0.1.0";

    private final UseDocuments documents;
    private final WorkspaceService workspace = new WorkspaceService() {
        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        }
    };

    UseLanguageServer(UseChecker checker) {
        documents = new UseDocuments(checker);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Full);
        sync.setSave(new SaveOptions(false));
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(sync);
        return CompletableFuture.completedFuture(
                new InitializeResult(capabilities, new ServerInfo("USE-Sprachserver", VERSION)));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        documents.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documents;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    @Override
    public void connect(LanguageClient client) {
        documents.connect(client);
    }
}

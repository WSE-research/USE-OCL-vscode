package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.junit.jupiter.api.Test;

class UseLanguageServerTest {

    private final UseLanguageServer server = new UseLanguageServer(TestSupport.checker());

    @Test
    void initializeAnnouncesFullSyncAndCompletion() throws Exception {
        InitializeResult result = server.initialize(new InitializeParams()).get(10, TimeUnit.SECONDS);
        ServerCapabilities capabilities = result.getCapabilities();
        TextDocumentSyncOptions sync = capabilities.getTextDocumentSync().getRight();
        assertTrue(sync.getOpenClose());
        assertEquals(TextDocumentSyncKind.Full, sync.getChange());
        assertFalse(sync.getSave().getRight().getIncludeText());
        assertEquals(List.of(".", ">", ":", "!"), capabilities.getCompletionProvider().getTriggerCharacters());
        assertFalse(capabilities.getCompletionProvider().getResolveProvider());
        assertEquals("USE-Sprachserver", result.getServerInfo().getName());
        server.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void versionMatchesThePom() {
        // package.json, pom.xml and VERSION change together; surefire passes the pom's version
        assertEquals(System.getProperty("project.version"), UseLanguageServer.VERSION);
    }

    @Test
    void documentsGoToUseDocumentsAndDiagnosticsToTheClient() throws Exception {
        assertTrue(server.getTextDocumentService() instanceof UseDocuments);
        assertSame(server.getTextDocumentService(), server.getTextDocumentService());
        UseDocumentsTest.Client client = new UseDocumentsTest.Client();
        server.connect(client);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem("untitled:Untitled-1", "soil", 1, "hilfe")));
        assertEquals(1, client.next().getDiagnostics().size());
        assertNull(server.shutdown().get(10, TimeUnit.SECONDS));
    }

    @Test
    void workspaceNotificationsAreIgnored() {
        server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(Map.of()));
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of()));
        server.shutdown();
    }
}

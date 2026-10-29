package de.htwk.uselsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void useHomeComesFromTheSystemProperty() {
        String saved = System.getProperty("use.home");
        try {
            System.setProperty("use.home", "/opt/use");
            assertEquals(Path.of("/opt/use"), Main.useHome());
            System.setProperty("use.home", " ");
            assertNull(Main.useHome());
            System.clearProperty("use.home");
            assertNull(Main.useHome());
        } finally {
            if (saved != null) {
                System.setProperty("use.home", saved);
            }
        }
    }

    /** The server in its own JVM, over stdin/stdout: USE's own output must not disturb the protocol. */
    @Test
    void serverSpeaksLspOverStdio() throws Exception {
        Process process = TestSupport.start("de.htwk.uselsp.Main");
        try {
            BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
            LanguageClient client = new LanguageClient() {
                @Override
                public void publishDiagnostics(PublishDiagnosticsParams params) {
                    diagnostics.add(params);
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
            };
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(),
                    process.getOutputStream());
            launcher.startListening();
            LanguageServer server = launcher.getRemoteProxy();

            InitializeResult result = server.initialize(new InitializeParams()).get(60, TimeUnit.SECONDS);
            assertEquals(UseLanguageServer.VERSION, result.getServerInfo().getVersion());
            server.initialized(null);
            // this model makes USE print a warning, which it writes to System.out
            String uri = TestSupport.SAMPLES.resolve("bibliothek-fehler.use").toUri().toString();
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "use", 1, TestSupport.sample("bibliothek-fehler.use"))));
            PublishDiagnosticsParams p = diagnostics.poll(60, TimeUnit.SECONDS);
            assertNotNull(p, "no diagnostics");
            assertEquals(4, p.getDiagnostics().size());

            server.shutdown().get(30, TimeUnit.SECONDS);
            server.exit();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "server did not exit");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }
}

package de.htwk.uselsp;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;

/**
 * Language server for USE models (.use) and Soil scripts (.soil), speaks LSP over
 * stdin/stdout.
 *
 * <p>Usage: {@code java -Duse.home=<USE-dir> -cp <server>/*:<USE-dir>/lib/* de.htwk.uselsp.Main}
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        PrintStream out = System.out;
        // stdout belongs to the protocol. USE writes messages to System.out and
        // System.err; both must be redirected before the first USE class loads.
        Capture.install();

        UseLanguageServer server = new UseLanguageServer(new UseChecker(useHome()));
        Launcher<UseClient> launcher = new LSPLauncher.Builder<UseClient>().setLocalService(server)
                .setRemoteInterface(UseClient.class).setInput(in).setOutput(out).create();
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
        System.exit(0);
    }

    /** The USE installation from {@code -Duse.home}, for the OCL extensions. */
    static Path useHome() {
        String home = System.getProperty("use.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }
}

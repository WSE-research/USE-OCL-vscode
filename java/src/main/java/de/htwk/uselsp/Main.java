package de.htwk.uselsp;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;

/**
 * Sprachserver für USE-Modelle (.use) und Soil-Skripte (.soil), spricht LSP über
 * stdin/stdout.
 *
 * <p>Aufruf: {@code java -Duse.home=<USE-Ordner> -cp <server>/*:<USE-Ordner>/lib/* de.htwk.uselsp.Main}
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        PrintStream out = System.out;
        // stdout gehört dem Protokoll. USE schreibt Meldungen nach System.out und
        // System.err; das muss umgelenkt sein, bevor die erste USE-Klasse lädt.
        Capture.install();

        UseLanguageServer server = new UseLanguageServer(new UseChecker(useHome()));
        Launcher<UseClient> launcher = new LSPLauncher.Builder<UseClient>().setLocalService(server)
                .setRemoteInterface(UseClient.class).setInput(in).setOutput(out).create();
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
        System.exit(0);
    }

    /** Die USE-Installation aus {@code -Duse.home}, für die OCL-Erweiterungen. */
    static Path useHome() {
        String home = System.getProperty("use.home");
        return home == null || home.isBlank() ? null : Path.of(home);
    }
}

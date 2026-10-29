package de.htwk.uselsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Der Client mit einer eigenen Anfrage: die Modelle neben einem Soil-Skript.
 * Der Server liest selbst keine Dateien (im Container sieht er nur seinen Code);
 * für die Vervollständigung in Skripten schickt ihm die Erweiterung die
 * {@code .use}-Dateien aus dem Ordner des Skripts.
 */
interface UseClient extends LanguageClient {

    record ModelsParams(String uri) {
    }

    record ModelFile(String uri, String text) {
    }

    @JsonRequest("useOcl/models")
    CompletableFuture<List<ModelFile>> models(ModelsParams params);
}

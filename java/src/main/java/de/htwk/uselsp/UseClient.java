package de.htwk.uselsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The client with a custom request: the models next to a Soil script.
 * The server reads no files itself (in the container it sees only its own code);
 * for completion in scripts, the extension sends it the {@code .use} files from
 * the script's folder.
 */
interface UseClient extends LanguageClient {

    record ModelsParams(String uri) {
    }

    record ModelFile(String uri, String text) {
    }

    @JsonRequest("useOcl/models")
    CompletableFuture<List<ModelFile>> models(ModelsParams params);
}

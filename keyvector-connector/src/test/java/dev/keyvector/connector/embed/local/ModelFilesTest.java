package dev.keyvector.connector.embed.local;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelFilesTest {

    @TempDir
    Path tmp;

    @Test
    void repoIdsAndRevisionsAreValidated() {
        assertDoesNotThrow(() -> ModelFiles.hub("sentence-transformers/all-MiniLM-L6-v2", "main", tmp));
        assertDoesNotThrow(() -> ModelFiles.hub("BAAI/bge-small-en-v1.5", "5c38ec7c405ec4b44b94cc5a9bb96e735b38267a", tmp));
        for (String bad : new String[] {"all-MiniLM-L6-v2", "../etc/passwd", "a/b/c", "a/..", "https://evil/x", "", " a/b"}) {
            assertThrows(IllegalArgumentException.class, () -> ModelFiles.hub(bad, "main", tmp), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ModelFiles.hub("a/b", "../main", tmp));
    }

    @Test
    void aLocalDirectoryIsReadWithoutDownloadingAndCannotBeEscaped() throws Exception {
        Files.writeString(tmp.resolve("tokenizer.json"), "{}");
        ModelFiles files = ModelFiles.local(tmp);
        assertEquals(tmp.resolve("tokenizer.json"), files.optional("tokenizer.json"));
        assertNull(files.optional("onnx/model.onnx"));
        assertThrows(java.io.IOException.class, () -> files.required("modules.json"));
        assertThrows(IllegalArgumentException.class, () -> files.optional("../secret"));
        assertThrows(IllegalArgumentException.class, () -> files.optional("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> ModelFiles.local(tmp.resolve("missing")));
    }
}

package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIdentityTest {

    @TempDir
    Path tempDir;

    @Test
    void documentId_shouldBeStableAndExpressionSafe() {
        String first = DocumentIdentity.documentId("runbook.md");
        String second = DocumentIdentity.documentId("runbook.md");

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    void contentHash_shouldChangeWhenContentChanges() throws Exception {
        Path file = tempDir.resolve("runbook.md");
        Files.writeString(file, "one");
        String first = DocumentIdentity.contentHash(file);
        Files.writeString(file, "two");

        assertTrue(!first.equals(DocumentIdentity.contentHash(file)));
    }
}

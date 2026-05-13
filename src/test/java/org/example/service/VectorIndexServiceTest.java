package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildMetadata_shouldMarkUploadedChunksAsDocumentData() {
        VectorIndexService service = new VectorIndexService();
        DocumentChunk chunk = new DocumentChunk("content", 0, 7, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = ReflectionTestUtils.invokeMethod(
                service, "buildMetadata", "/tmp/runbook.md", chunk, 3);

        assertEquals("document", metadata.get("doc_type"));
        assertEquals("/tmp/runbook.md", metadata.get("_source"));
        assertEquals(2, metadata.get("chunkIndex"));
        assertEquals(3, metadata.get("totalChunks"));
    }

    @Test
    void indexSingleFile_shouldBatchEmbeddingsAndInsertChunksTogether() throws Exception {
        Path file = tempDir.resolve("runbook.md");
        Files.writeString(file, "# Runbook\nchunk one\nchunk two");

        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        DocumentChunkService chunkService = mock(DocumentChunkService.class);

        @SuppressWarnings("unchecked")
        R<RpcStatus> loadResponse = mock(R.class);
        when(loadResponse.getStatus()).thenReturn(0);
        when(milvusClient.loadCollection(any(LoadCollectionParam.class))).thenReturn(loadResponse);

        @SuppressWarnings("unchecked")
        R<MutationResult> mutationResponse = mock(R.class);
        when(mutationResponse.getStatus()).thenReturn(0);
        when(mutationResponse.getData()).thenReturn(MutationResult.getDefaultInstance());
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(mutationResponse);
        when(milvusClient.insert(any(InsertParam.class))).thenReturn(mutationResponse);

        List<DocumentChunk> chunks = List.of(
                new DocumentChunk("chunk one", 0, 9, 0),
                new DocumentChunk("chunk two", 10, 19, 1)
        );
        when(chunkService.chunkDocument(any(), any())).thenReturn(chunks);
        when(embeddingService.generateEmbeddings(List.of("chunk one", "chunk two")))
                .thenReturn(List.of(List.of(0.1f, 0.2f), List.of(0.3f, 0.4f)));
        SortedMap<Long, Float> sparseOne = new TreeMap<>(Map.of(1L, 1.0f));
        SortedMap<Long, Float> sparseTwo = new TreeMap<>(Map.of(2L, 1.0f));
        when(embeddingService.generateSparseVector("chunk one")).thenReturn(sparseOne);
        when(embeddingService.generateSparseVector("chunk two")).thenReturn(sparseTwo);

        VectorIndexService service = new VectorIndexService();
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "chunkService", chunkService);

        service.indexSingleFile(file.toString());

        verify(embeddingService).generateEmbeddings(List.of("chunk one", "chunk two"));
        verify(embeddingService, never()).generateEmbedding(any());
        verify(milvusClient).loadCollection(any(LoadCollectionParam.class));

        ArgumentCaptor<InsertParam> insertCaptor = ArgumentCaptor.forClass(InsertParam.class);
        verify(milvusClient).insert(insertCaptor.capture());
        InsertParam insertParam = insertCaptor.getValue();

        assertEquals(2, insertParam.getRowCount());
        assertEquals(2, fieldValues(insertParam, "content").size());
        assertEquals(2, fieldValues(insertParam, "vector").size());
        assertEquals(2, fieldValues(insertParam, "sparse_vector").size());
        assertEquals(2, fieldValues(insertParam, "metadata").size());
    }

    private List<?> fieldValues(InsertParam insertParam, String fieldName) {
        return insertParam.getFields().stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getValues();
    }
}

package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import org.example.config.AppResilienceProperties;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorEmbeddingServiceTest {

    @Test
    void generateEmbeddingsSplitsRequestsIntoDashScopeLimitBatches() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        List<Integer> batchSizes = new ArrayList<>();
        when(textEmbedding.call(any(TextEmbeddingParam.class))).thenAnswer(invocation -> {
            TextEmbeddingParam param = invocation.getArgument(0);
            int batchSize = param.getInput().getAsJsonArray("texts").size();
            batchSizes.add(batchSize);
            return embeddingResult(batchSize);
        });

        List<String> contents = IntStream.range(0, 11)
                .mapToObj(index -> "content-" + index)
                .toList();

        List<List<Float>> embeddings = service.generateEmbeddings(contents);

        assertEquals(11, embeddings.size());
        assertEquals(List.of(10, 1), batchSizes);
    }

    @Test
    void generateEmbedding_shouldThrowCircuitOpenAfterBreakerOpens() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        ReflectionTestUtils.setField(service, "dependencyGuard", new DependencyGuard(properties));

        when(textEmbedding.call(any(TextEmbeddingParam.class)))
                .thenThrow(new RuntimeException("dashscope unavailable"));

        assertThrows(RuntimeException.class, () -> service.generateEmbedding("content"));

        RuntimeException open = assertThrows(RuntimeException.class, () -> service.generateEmbedding("content"));

        assertInstanceOf(DependencyUnavailableException.class, open);
        assertEquals("CIRCUIT_OPEN", ((DependencyUnavailableException) open).getErrorCode());
        verify(textEmbedding, times(1)).call(any(TextEmbeddingParam.class));
    }

    @Test
    void generateEmbeddings_shouldRejectBlankOrNullItemBeforeCallingDashScope() {
        VectorEmbeddingService blankService = new VectorEmbeddingService();
        TextEmbedding blankTextEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(blankService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(blankService, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(blankService, "textEmbedding", blankTextEmbedding);

        assertThrows(IllegalArgumentException.class,
                () -> blankService.generateEmbeddings(List.of("content", " ")));
        verifyNoInteractions(blankTextEmbedding);

        VectorEmbeddingService nullService = new VectorEmbeddingService();
        TextEmbedding nullTextEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(nullService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(nullService, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(nullService, "textEmbedding", nullTextEmbedding);

        assertThrows(IllegalArgumentException.class,
                () -> nullService.generateEmbeddings(Arrays.asList("content", null)));
        verifyNoInteractions(nullTextEmbedding);
    }

    @Test
    void generateEmbeddings_shouldReorderBatchResultsByTextIndex() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        TextEmbeddingResult result = embeddingResult(
                item(2, List.of(2.0D, 2.1D)),
                item(0, List.of(0.0D, 0.1D)),
                item(1, List.of(1.0D, 1.1D)));
        when(textEmbedding.call(any(TextEmbeddingParam.class))).thenReturn(result);

        List<List<Float>> embeddings = service.generateEmbeddings(List.of("first", "second", "third"));

        assertEquals(List.of(0.0f, 0.1f), embeddings.get(0));
        assertEquals(List.of(1.0f, 1.1f), embeddings.get(1));
        assertEquals(List.of(2.0f, 2.1f), embeddings.get(2));
    }

    @Test
    void generateEmbeddings_shouldRejectDuplicateTextIndex() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        TextEmbeddingResult result = embeddingResult(
                item(0, List.of(0.0D, 0.1D)),
                item(1, List.of(1.0D, 1.1D)),
                item(1, List.of(9.0D, 9.1D)));
        when(textEmbedding.call(any(TextEmbeddingParam.class))).thenReturn(result);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.generateEmbeddings(List.of("first", "second", "third")));

        assertTrue(failure.getMessage().contains("重复 textIndex"));
    }

    @Test
    void generateEmbeddings_shouldRejectOutOfRangeTextIndex() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        TextEmbeddingResult result = embeddingResult(
                item(0, List.of(0.0D, 0.1D)),
                item(1, List.of(1.0D, 1.1D)),
                item(3, List.of(3.0D, 3.1D)));
        when(textEmbedding.call(any(TextEmbeddingParam.class))).thenReturn(result);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.generateEmbeddings(List.of("first", "second", "third")));

        assertTrue(failure.getMessage().contains("无效 textIndex"));
    }

    @Test
    void generateEmbeddings_shouldRejectMissingEmbeddingItemForInputPosition() throws Exception {
        VectorEmbeddingService service = new VectorEmbeddingService();
        TextEmbedding textEmbedding = mock(TextEmbedding.class);
        ReflectionTestUtils.setField(service, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(service, "textEmbedding", textEmbedding);

        TextEmbeddingResult result = embeddingResult(
                item(0, List.of(0.0D, 0.1D)),
                item(2, List.of(2.0D, 2.1D)));
        when(textEmbedding.call(any(TextEmbeddingParam.class))).thenReturn(result);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.generateEmbeddings(List.of("first", "second", "third")));

        assertTrue(failure.getMessage().contains("向量数量与输入数量不一致"));
    }

    private TextEmbeddingResult embeddingResult(int size) {
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        List<TextEmbeddingResultItem> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TextEmbeddingResultItem item = new TextEmbeddingResultItem();
            item.setTextIndex(i);
            item.setEmbedding(List.of(0.1D, 0.2D, 0.3D));
            items.add(item);
        }
        output.setEmbeddings(items);

        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private TextEmbeddingResult embeddingResult(TextEmbeddingResultItem... items) {
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(Arrays.asList(items));

        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private TextEmbeddingResultItem item(int textIndex, List<Double> embedding) {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem();
        item.setTextIndex(textIndex);
        item.setEmbedding(embedding);
        return item;
    }
}

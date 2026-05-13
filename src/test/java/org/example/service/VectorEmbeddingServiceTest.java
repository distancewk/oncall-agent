package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}

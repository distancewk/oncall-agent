package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.param.dml.InsertParam;
import org.example.constant.MilvusConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

@Component
public class MilvusInsertHelper {

    private final Gson gson = new Gson();

    public InsertParam buildInsertParam(List<String> ids,
                                        List<String> contents,
                                        List<List<Float>> vectors,
                                        List<SortedMap<Long, Float>> sparseVectors,
                                        List<Map<String, Object>> metadataList) {
        validateEqualSizes(ids, contents, vectors, sparseVectors, metadataList);

        List<JsonObject> metadataJsonList = new ArrayList<>(metadataList.size());
        for (Map<String, Object> metadata : metadataList) {
            metadataJsonList.add(gson.toJsonTree(metadata).getAsJsonObject());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", ids));
        fields.add(new InsertParam.Field("content", contents));
        fields.add(new InsertParam.Field("vector", vectors));
        fields.add(new InsertParam.Field("sparse_vector", sparseVectors));
        fields.add(new InsertParam.Field("metadata", metadataJsonList));

        return InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFields(fields)
                .build();
    }

    private void validateEqualSizes(List<?>... lists) {
        int expected = lists[0].size();
        for (List<?> list : lists) {
            if (list.size() != expected) {
                throw new IllegalArgumentException("Milvus insert field sizes must match");
            }
        }
    }
}

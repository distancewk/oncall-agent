package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClsClientTest {

    @Test
    void buildBody_shouldUseMillisecondsAndBoundLimit() throws Exception {
        ClsClient client = new ClsClient(new OkHttpClient());
        String body = client.buildBody(new ClsClient.SearchRequest(
                "https://cls.example.test", "/", "topic-1", 1_725_000_000_000L,
                1_725_000_300_000L, "level:error", 5_000,
                "id", "secret", "ap-guangzhou", "cls", 2_000));

        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals(1_725_000_000_000L, json.path("From").asLong());
        assertEquals(1_725_000_300_000L, json.path("To").asLong());
        assertEquals(1_000, json.path("Limit").asInt());
        assertTrue(json.path("QueryString").asText().contains("level:error"));
    }
}

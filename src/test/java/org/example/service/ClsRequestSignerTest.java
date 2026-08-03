package org.example.service;

import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClsRequestSignerTest {

    @Test
    void sign_shouldProduceTc3HeadersWithoutExposingSecret() {
        URI uri = URI.create("https://cls.example.test/api/v1/logs/query?limit=1");
        Request.Builder builder = new Request.Builder().url(uri.toString()).get();

        ClsRequestSigner.sign(builder, uri, "GET", "", "id", "secret-key",
                "ap-guangzhou", "cls", Instant.ofEpochSecond(1_725_000_000));

        Request request = builder.build();
        assertTrue(request.header("Authorization").startsWith("TC3-HMAC-SHA256 Credential=id/"));
        assertTrue(request.header("Authorization").contains("SignedHeaders=content-type;host"));
        assertTrue(!request.header("Authorization").contains("secret-key"));
    }
}

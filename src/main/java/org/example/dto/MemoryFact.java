package org.example.dto;

public record MemoryFact(String content,
                         String type,
                         double confidence,
                         double importance,
                         Long expiresAt) {
}

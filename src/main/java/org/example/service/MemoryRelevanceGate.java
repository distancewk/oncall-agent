package org.example.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic gate for long-term memory retrieval.
 * It deliberately prefers false negatives over paying for a vector search on every chat.
 */
@Component
public class MemoryRelevanceGate {

    private static final Pattern MEMORY_MARKERS = Pattern.compile(
            "(?:记住|记忆|之前|上次|历史对话|我的偏好|我习惯|我喜欢|你还记得|按照我的"
                    + "|remember|memory|previous|last time|my preference|my habit)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public boolean shouldRecall(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return MEMORY_MARKERS.matcher(question.toLowerCase(Locale.ROOT)).find();
    }
}

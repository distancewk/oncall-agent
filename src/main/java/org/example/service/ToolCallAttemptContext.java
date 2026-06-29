package org.example.service;

import org.springframework.stereotype.Service;

@Service
public class ToolCallAttemptContext {

    private static final int DEFAULT_ATTEMPTS = 1;

    private final ThreadLocal<Integer> lastAttempts = new ThreadLocal<>();

    public void recordAttempts(int attempts) {
        lastAttempts.set(Math.max(0, attempts));
    }

    public int consumeLastAttempts() {
        Integer attempts = lastAttempts.get();
        lastAttempts.remove();
        return attempts == null ? DEFAULT_ATTEMPTS : Math.max(0, attempts);
    }
}

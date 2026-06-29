package org.example.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppMemoryPropertiesTest {

    @Test
    void privateRecallMaxPromptChars_shouldStayPositive() {
        AppMemoryProperties properties = new AppMemoryProperties();

        properties.setPrivateRecallMaxPromptChars(0);

        assertEquals(1, properties.getPrivateRecallMaxPromptChars());
    }
}

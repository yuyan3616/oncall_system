package org.example.memory;

import org.example.config.ChatMemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryServiceTest {

    @Test
    void shouldCompressWhenRecentWindowExceeded() throws Exception {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setEnabled(true);
        properties.setRecentPairs(1);
        properties.setCompressBatchPairs(1);
        properties.setMaxSummaryChars(500);

        MemoryCompressionService compressionService = (existing, batch) -> {
            String base = existing == null ? "" : existing;
            String user = batch.isEmpty() ? "" : batch.get(0).getOrDefault("content", "");
            return (base + " summary:" + user).trim();
        };

        ConversationMemoryService service = new ConversationMemoryService(properties, compressionService);
        try {
            service.appendMessagePair("s1", "q1", "a1");
            service.appendMessagePair("s1", "q2", "a2");

            waitUntil(() -> service.snapshot("s1").getCompressedMessagePairs() >= 1, 1500);
            ConversationMemoryState state = service.snapshot("s1");

            assertEquals(2, state.getTotalMessagePairs());
            assertEquals(1, state.getCompressedMessagePairs());
            assertEquals(2, state.getRecentMessages().size());
            assertEquals("q2", state.getRecentMessages().get(0).get("content"));
            assertTrue(state.getSummary().contains("q1"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldClearSessionMemory() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setEnabled(true);
        MemoryCompressionService compressionService = (existing, batch) -> existing;
        ConversationMemoryService service = new ConversationMemoryService(properties, compressionService);
        try {
            service.appendMessagePair("s2", "hello", "world");
            assertEquals(1, service.getMessagePairCount("s2"));

            service.clear("s2");

            ConversationMemoryState state = service.snapshot("s2");
            assertEquals(0, state.getTotalMessagePairs());
            assertTrue(state.getRecentMessages().isEmpty());
        } finally {
            service.shutdown();
        }
    }

    private void waitUntil(Check check, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("timeout waiting condition");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}


package org.example.memory;

import org.example.config.ChatMemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMemoryCompressionServiceTest {

    @Test
    void shouldFallbackToRuleCompressionWhenLlmUnavailable() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setLlmEnabled(true);
        properties.setMaxSummaryChars(600);

        DefaultMemoryCompressionService service =
                new DefaultMemoryCompressionService(properties, "", "qwen3-max");

        String summary = service.compress(
                "历史摘要",
                List.of(
                        Map.of("role", "user", "content", "数据库连接池告警持续出现"),
                        Map.of("role", "assistant", "content", "建议先检查连接数上限与慢查询")
                )
        );

        assertTrue(summary.contains("历史摘要"));
        assertTrue(summary.contains("数据库连接池告警持续出现"));
        service.shutdown();
    }
}


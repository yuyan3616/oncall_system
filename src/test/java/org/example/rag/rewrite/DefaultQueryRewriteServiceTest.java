package org.example.rag.rewrite;

import org.example.rag.config.RagRewriteProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryRewriteServiceTest {

    @Test
    void shouldNormalizeAndSplitWhenLlmDisabled() {
        RagRewriteProperties properties = new RagRewriteProperties();
        properties.setEnabled(true);
        properties.setLlmEnabled(false);
        properties.setMaxSubQuestions(3);

        DefaultQueryRewriteService service =
                new DefaultQueryRewriteService(properties, "", "qwen3-max");

        RewriteResult result = service.rewrite("请问帮我排查订单服务响应慢；另外报错怎么办？");

        assertFalse(result.isLlmApplied());
        assertTrue(result.getRewrittenQuestion().contains("诊断"));
        assertTrue(result.getRewrittenQuestion().contains("慢响应"));
        assertEquals(2, result.getSubQuestions().size());
    }

    @Test
    void shouldFallbackWhenRewriteDisabled() {
        RagRewriteProperties properties = new RagRewriteProperties();
        properties.setEnabled(false);
        properties.setLlmEnabled(false);

        DefaultQueryRewriteService service =
                new DefaultQueryRewriteService(properties, "", "qwen3-max");

        RewriteResult result = service.rewrite("原始问题");

        assertEquals("原始问题", result.getNormalizedQuestion());
        assertEquals("原始问题", result.getRewrittenQuestion());
        assertEquals(1, result.getSubQuestions().size());
    }
}

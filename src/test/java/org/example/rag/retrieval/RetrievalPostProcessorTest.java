package org.example.rag.retrieval;

import org.example.rag.config.RagSearchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalPostProcessorTest {

    @Test
    void shouldDeduplicateByIdAndKeepTopK() {
        RagSearchProperties properties = new RagSearchProperties();
        properties.getPostprocess().setDedupEnabled(true);
        properties.getPostprocess().setRerankEnabled(true);

        RetrievalPostProcessor processor = new RetrievalPostProcessor(properties);
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.builder()
                        .id("same-id")
                        .content("订单服务错误排查文档")
                        .metadata("{\"title\":\"订单故障\"}")
                        .rawScore(0.2F)
                        .channel("original")
                        .queryUsed("订单错误")
                        .build(),
                RetrievalCandidate.builder()
                        .id("same-id")
                        .content("重复文档，分值更差")
                        .metadata("{}")
                        .rawScore(1.8F)
                        .channel("rewritten")
                        .queryUsed("订单错误")
                        .build(),
                RetrievalCandidate.builder()
                        .id("another")
                        .content("支付服务诊断指南")
                        .metadata("{\"title\":\"支付服务\"}")
                        .rawScore(0.3F)
                        .channel("subquestion")
                        .queryUsed("支付服务异常")
                        .build()
        );

        List<RetrievalCandidate> output = processor.process("订单 错误", input, 2);

        assertEquals(2, output.size());
        assertTrue(output.stream().anyMatch(item -> "same-id".equals(item.getId())));
        assertTrue(output.stream().anyMatch(item -> "another".equals(item.getId())));
    }
}

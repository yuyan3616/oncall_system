package org.example.rag.retrieval;

import org.example.rag.config.RagSearchProperties;
import org.example.rag.rewrite.QueryRewriteService;
import org.example.rag.rewrite.RewriteResult;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalServiceTest {

    @Test
    void shouldContinueWhenOneChannelFails() {
        QueryRewriteService rewriteService = Mockito.mock(QueryRewriteService.class);
        VectorSearchService vectorSearchService = Mockito.mock(VectorSearchService.class);

        RagSearchProperties properties = new RagSearchProperties();
        properties.getChannels().setTopKPerChannel(2);
        properties.getChannels().setOriginalEnabled(true);
        properties.getChannels().setRewrittenEnabled(true);
        properties.getChannels().setSubquestionEnabled(true);

        RetrievalPostProcessor postProcessor = new RetrievalPostProcessor(properties);

        MultiChannelRetrievalService service = new MultiChannelRetrievalService(
                rewriteService, vectorSearchService, postProcessor, properties, 3
        );

        RewriteResult rewriteResult = RewriteResult.builder()
                .normalizedQuestion("订单错误")
                .rewrittenQuestion("订单服务 错误 诊断")
                .subQuestions(List.of("订单服务错误", "日志排查"))
                .llmApplied(false)
                .build();
        when(rewriteService.rewrite(eq("订单错误"))).thenReturn(rewriteResult);

        VectorSearchService.SearchResult original = new VectorSearchService.SearchResult();
        original.setId("doc-1");
        original.setContent("订单错误处理手册");
        original.setScore(0.2F);
        original.setMetadata("{\"title\":\"订单故障\"}");

        VectorSearchService.SearchResult rewritten = new VectorSearchService.SearchResult();
        rewritten.setId("doc-2");
        rewritten.setContent("订单诊断流程");
        rewritten.setScore(0.3F);
        rewritten.setMetadata("{}");

        when(vectorSearchService.searchSimilarDocuments(eq("订单错误"), anyInt()))
                .thenReturn(List.of(original));
        when(vectorSearchService.searchSimilarDocuments(eq("订单服务 错误 诊断"), anyInt()))
                .thenReturn(List.of(rewritten));
        when(vectorSearchService.searchSimilarDocuments(eq("订单服务错误"), anyInt()))
                .thenThrow(new RuntimeException("mock channel failure"));
        when(vectorSearchService.searchSimilarDocuments(eq("日志排查"), anyInt()))
                .thenReturn(List.of());

        MultiChannelRetrievalResult result = assertDoesNotThrow(() -> service.retrieve("订单错误", 3));
        assertFalse(result.getFinalCandidates().isEmpty());
    }
}

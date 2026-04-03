package org.example.agent.tool;

import org.example.rag.retrieval.MultiChannelRetrievalResult;
import org.example.rag.retrieval.MultiChannelRetrievalService;
import org.example.rag.retrieval.RetrievalCandidate;
import org.example.rag.rewrite.RewriteResult;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class InternalDocsToolsTest {

    @Test
    void shouldReturnCompatibleJsonList() {
        MultiChannelRetrievalService retrievalService = Mockito.mock(MultiChannelRetrievalService.class);
        InternalDocsTools tools = new InternalDocsTools(retrievalService);
        ReflectionTestUtils.setField(tools, "topK", 3);

        RewriteResult rewriteResult = RewriteResult.builder()
                .normalizedQuestion("订单错误")
                .rewrittenQuestion("订单 错误 诊断")
                .subQuestions(List.of("订单错误"))
                .llmApplied(false)
                .build();

        RetrievalCandidate candidate = RetrievalCandidate.builder()
                .id("doc-1")
                .content("订单错误处理流程")
                .metadata("{\"title\":\"订单文档\"}")
                .rawScore(0.2F)
                .channel("original")
                .queryUsed("订单错误")
                .finalScore(0.88D)
                .build();

        MultiChannelRetrievalResult retrievalResult = MultiChannelRetrievalResult.builder()
                .originalQuestion("订单错误")
                .rewriteResult(rewriteResult)
                .mergedCandidates(List.of(candidate))
                .finalCandidates(List.of(candidate))
                .build();

        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId("doc-1");
        result.setContent("订单错误处理流程");
        result.setMetadata("{\"title\":\"订单文档\"}");
        result.setScore(0.2F);
        result.setChannel("original");
        result.setQueryUsed("订单错误");
        result.setFinalScore(0.88D);

        when(retrievalService.retrieve(eq("订单错误"), anyInt())).thenReturn(retrievalResult);
        when(retrievalService.toSearchResults(eq(List.of(candidate)))).thenReturn(List.of(result));

        String json = tools.queryInternalDocs("订单错误");
        assertTrue(json.startsWith("["));
        assertTrue(json.contains("\"id\":\"doc-1\""));
        assertTrue(json.contains("\"channel\":\"original\""));
    }
}

package org.example.service;

import org.example.rag.retrieval.MultiChannelRetrievalResult;
import org.example.rag.retrieval.MultiChannelRetrievalService;
import org.example.rag.retrieval.RetrievalCandidate;
import org.example.rag.rewrite.RewriteResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class RagServiceTest {

    @Test
    void shouldReturnNoResultMessageWhenCandidatesEmpty() {
        MultiChannelRetrievalService retrievalService = Mockito.mock(MultiChannelRetrievalService.class);
        RagService ragService = new RagService(retrievalService);
        ReflectionTestUtils.setField(ragService, "topK", 3);

        RewriteResult rewriteResult = RewriteResult.builder()
                .normalizedQuestion("test")
                .rewrittenQuestion("test")
                .subQuestions(List.of("test"))
                .llmApplied(false)
                .build();

        MultiChannelRetrievalResult retrievalResult = MultiChannelRetrievalResult.builder()
                .originalQuestion("test")
                .rewriteResult(rewriteResult)
                .mergedCandidates(List.of())
                .finalCandidates(List.of())
                .build();
        when(retrievalService.retrieve(eq("test"), anyInt())).thenReturn(retrievalResult);
        when(retrievalService.toSearchResults(eq(List.of()))).thenReturn(List.of());

        List<String> completed = new ArrayList<>();
        ragService.queryStream("test", List.of(Map.of("role", "user", "content", "hello")), new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                assertTrue(results.isEmpty());
            }

            @Override
            public void onReasoningChunk(String chunk) {
            }

            @Override
            public void onContentChunk(String chunk) {
            }

            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                completed.add(fullContent);
            }

            @Override
            public void onError(Exception e) {
            }
        });

        assertEquals(1, completed.size());
        assertTrue(completed.get(0).contains("没有找到相关信息"));
    }
}

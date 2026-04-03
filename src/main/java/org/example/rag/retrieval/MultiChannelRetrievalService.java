package org.example.rag.retrieval;

import org.example.rag.config.RagSearchProperties;
import org.example.rag.rewrite.QueryRewriteService;
import org.example.rag.rewrite.RewriteResult;
import org.example.service.VectorSearchService;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MultiChannelRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(MultiChannelRetrievalService.class);
    private static final String CHANNEL_ORIGINAL = "original";
    private static final String CHANNEL_REWRITTEN = "rewritten";
    private static final String CHANNEL_SUBQUESTION = "subquestion";

    private final QueryRewriteService queryRewriteService;
    private final VectorSearchService vectorSearchService;
    private final RetrievalPostProcessor retrievalPostProcessor;
    private final RagSearchProperties ragSearchProperties;
    private final int ragTopK;
    private final ExecutorService channelExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread thread = new Thread(r);
        thread.setName("retrieval-channel-executor");
        thread.setDaemon(true);
        return thread;
    });

    public MultiChannelRetrievalService(QueryRewriteService queryRewriteService,
                                        VectorSearchService vectorSearchService,
                                        RetrievalPostProcessor retrievalPostProcessor,
                                        RagSearchProperties ragSearchProperties,
                                        @Value("${rag.top-k:3}") int ragTopK) {
        this.queryRewriteService = queryRewriteService;
        this.vectorSearchService = vectorSearchService;
        this.retrievalPostProcessor = retrievalPostProcessor;
        this.ragSearchProperties = ragSearchProperties;
        this.ragTopK = ragTopK;
    }

    public MultiChannelRetrievalResult retrieve(String question) {
        return retrieve(question, ragTopK);
    }

    public MultiChannelRetrievalResult retrieve(String question, int finalTopK) {
        long start = System.currentTimeMillis();
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isEmpty()) {
            return MultiChannelRetrievalResult.builder()
                    .originalQuestion("")
                    .rewriteResult(RewriteResult.builder()
                            .normalizedQuestion("")
                            .rewrittenQuestion("")
                            .subQuestions(List.of())
                            .llmApplied(false)
                            .build())
                    .mergedCandidates(List.of())
                    .finalCandidates(List.of())
                    .build();
        }

        TraceLogger.info(logger, "retrieval_start", "questionLength", safeQuestion.length());
        RewriteResult rewriteResult = queryRewriteService.rewrite(safeQuestion);
        TraceLogger.info(logger, "retrieval_rewrite_ready",
                "subQuestionCount", rewriteResult.getSubQuestions() == null ? 0 : rewriteResult.getSubQuestions().size(),
                "llmApplied", rewriteResult.isLlmApplied());

        int topKPerChannel = Math.max(1, ragSearchProperties.getChannels().getTopKPerChannel());
        List<CompletableFuture<List<RetrievalCandidate>>> futures = new ArrayList<>();

        if (ragSearchProperties.getChannels().isOriginalEnabled()) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> safeRetrieve(CHANNEL_ORIGINAL, safeQuestion, topKPerChannel),
                    channelExecutor
            ));
        }

        if (ragSearchProperties.getChannels().isRewrittenEnabled()) {
            String rewritten = rewriteResult.getRewrittenQuestion();
            if (rewritten != null && !rewritten.isBlank()) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> safeRetrieve(CHANNEL_REWRITTEN, rewritten, topKPerChannel),
                        channelExecutor
                ));
            }
        }

        if (ragSearchProperties.getChannels().isSubquestionEnabled()) {
            List<String> subQuestions = rewriteResult.getSubQuestions() == null
                    ? List.of()
                    : rewriteResult.getSubQuestions();
            if (!subQuestions.isEmpty()) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> safeRetrieveSubquestions(subQuestions, topKPerChannel),
                        channelExecutor
                ));
            }
        }

        List<RetrievalCandidate> merged = futures.isEmpty()
                ? List.of()
                : futures.stream().map(this::safeJoin).flatMap(List::stream).toList();
        List<RetrievalCandidate> finalCandidates = retrievalPostProcessor.process(
                rewriteResult.getRewrittenQuestion(),
                merged,
                Math.max(1, finalTopK)
        );

        TraceLogger.info(logger, "retrieval_complete",
                "mergedCount", merged.size(),
                "finalCount", finalCandidates.size(),
                "elapsedMs", System.currentTimeMillis() - start);

        return MultiChannelRetrievalResult.builder()
                .originalQuestion(safeQuestion)
                .rewriteResult(rewriteResult)
                .mergedCandidates(merged)
                .finalCandidates(finalCandidates)
                .build();
    }

    public List<VectorSearchService.SearchResult> toSearchResults(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<VectorSearchService.SearchResult> mapped = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
            result.setId(candidate.getId());
            result.setContent(candidate.getContent());
            result.setMetadata(candidate.getMetadata());
            result.setScore((float) candidate.getRawScore());
            result.setChannel(candidate.getChannel());
            result.setQueryUsed(candidate.getQueryUsed());
            result.setFinalScore(candidate.getFinalScore());
            mapped.add(result);
        }
        return mapped;
    }

    private List<RetrievalCandidate> safeRetrieveSubquestions(List<String> subQuestions, int topKPerChannel) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String subQuestion : subQuestions) {
            if (subQuestion != null && !subQuestion.isBlank()) {
                distinct.add(subQuestion.trim());
            }
        }
        if (distinct.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> merged = new ArrayList<>();
        for (String subQuestion : distinct) {
            merged.addAll(safeRetrieve(CHANNEL_SUBQUESTION, subQuestion, topKPerChannel));
        }
        return merged;
    }

    private List<RetrievalCandidate> safeRetrieve(String channel, String queryUsed, int topKPerChannel) {
        try {
            List<VectorSearchService.SearchResult> results =
                    vectorSearchService.searchSimilarDocuments(queryUsed, topKPerChannel);
            if (results == null || results.isEmpty()) {
                TraceLogger.info(logger, "channel_retrieve_empty", "channel", channel);
                return List.of();
            }
            List<RetrievalCandidate> mapped = new ArrayList<>(results.size());
            for (VectorSearchService.SearchResult result : results) {
                mapped.add(RetrievalCandidate.builder()
                        .id(result.getId())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .rawScore(result.getScore())
                        .channel(channel)
                        .queryUsed(queryUsed)
                        .build());
            }
            TraceLogger.info(logger, "channel_retrieve_success",
                    "channel", channel,
                    "queryLength", queryUsed == null ? 0 : queryUsed.length(),
                    "count", mapped.size());
            return mapped;
        } catch (Exception e) {
            TraceLogger.warn(logger, "channel_retrieve_failed",
                    "channel", channel,
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return Collections.emptyList();
        }
    }

    private List<RetrievalCandidate> safeJoin(CompletableFuture<List<RetrievalCandidate>> future) {
        try {
            List<RetrievalCandidate> result = future.join();
            return result == null ? List.of() : result;
        } catch (Exception e) {
            TraceLogger.warn(logger, "channel_join_failed",
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return List.of();
        }
    }

    @PreDestroy
    public void shutdown() {
        channelExecutor.shutdownNow();
    }
}

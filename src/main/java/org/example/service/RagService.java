package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.example.rag.retrieval.MultiChannelRetrievalResult;
import org.example.rag.retrieval.MultiChannelRetrievalService;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    private final MultiChannelRetrievalService retrievalService;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k:3}")
    private int topK;

    @Value("${rag.model:qwen-plus}")
    private String model;

    private Generation generation;

    public RagService(MultiChannelRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostConstruct
    public void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        generation = new Generation();
        logger.info("RAG service initialized, model: {}, topK: {}", model, topK);
    }

    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        try {
            TraceLogger.info(logger, "rag_query_received",
                    "questionLength", question == null ? 0 : question.length());

            MultiChannelRetrievalResult retrievalResult = retrievalService.retrieve(question, topK);
            List<VectorSearchService.SearchResult> searchResults =
                    retrievalService.toSearchResults(retrievalResult.getFinalCandidates());

            callback.onSearchResults(searchResults);
            TraceLogger.info(logger, "rag_retrieval_ready",
                    "finalCandidateCount", searchResults.size(),
                    "mergedCandidateCount", retrievalResult.getMergedCandidates().size());

            if (searchResults.isEmpty()) {
                logger.warn("No relevant documents found for question: {}", question);
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            String context = buildContext(searchResults);
            String prompt = buildPrompt(question, context);
            generateAnswerStream(prompt, history, callback);
        } catch (Exception e) {
            logger.error("RAG stream query failed", e);
            callback.onError(e);
        }
    }

    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料").append(i + 1).append("】\n");
            context.append(result.getContent() == null ? "" : result.getContent()).append("\n");
            if (result.getChannel() != null && !result.getChannel().isBlank()) {
                context.append("来源通道: ").append(result.getChannel()).append("\n");
            }
            context.append("\n");
        }
        return context.toString();
    }

    private String buildPrompt(String question, String context) {
        return String.format(
                "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n"
                        + "参考资料：\n%s\n"
                        + "用户问题：%s\n\n"
                        + "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
                context, question
        );
    }

    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallback callback)
            throws NoApiKeyException, ApiException, InputRequiredException {

        List<Message> messages = new ArrayList<>();
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        Flowable<GenerationResult> result = generation.streamCall(param);
        StringBuilder finalContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();

        result.blockingForEach(message -> {
            if (message.getOutput() == null
                    || message.getOutput().getChoices() == null
                    || message.getOutput().getChoices().isEmpty()
                    || message.getOutput().getChoices().get(0).getMessage() == null) {
                return;
            }
            String content = message.getOutput().getChoices().get(0).getMessage().getContent();
            if (content != null && !content.isEmpty()) {
                finalContent.append(content);
                callback.onContentChunk(content);
            }
        });

        callback.onComplete(finalContent.toString(), reasoningContent.toString());
    }

    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);

        void onReasoningChunk(String chunk);

        void onContentChunk(String chunk);

        void onComplete(String fullContent, String fullReasoning);

        void onError(Exception e);
    }
}

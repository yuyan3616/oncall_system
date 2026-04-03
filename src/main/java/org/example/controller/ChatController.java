package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.memory.ConversationMemoryService;
import org.example.memory.ConversationMemoryState;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.stability.model.ModelRoutingService;
import org.example.stability.model.NonRetryableModelException;
import org.example.stability.queue.RateLimitRejectedException;
import org.example.stability.queue.RequestQueueLimiter;
import org.example.stability.trace.TraceContext;
import org.example.stability.trace.TraceContextHolder;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private static final String BUSY_MESSAGE = "系统繁忙，请稍后再试";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    private final AiOpsService aiOpsService;
    private final ChatService chatService;
    private final ToolCallbackProvider tools;
    private final RequestQueueLimiter requestQueueLimiter;
    private final ModelRoutingService modelRoutingService;
    private final ConversationMemoryService conversationMemoryService;

    public ChatController(AiOpsService aiOpsService,
                          ChatService chatService,
                          ToolCallbackProvider tools,
                          RequestQueueLimiter requestQueueLimiter,
                          ModelRoutingService modelRoutingService,
                          ConversationMemoryService conversationMemoryService) {
        this.aiOpsService = aiOpsService;
        this.chatService = chatService;
        this.tools = tools;
        this.requestQueueLimiter = requestQueueLimiter;
        this.modelRoutingService = modelRoutingService;
        this.conversationMemoryService = conversationMemoryService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.getId());
        TraceContext context = TraceContextHolder.start("chat", "/api/chat", sessionId);
        TraceLogger.info(logger, "request_received",
                "questionLength", request.getQuestion() == null ? 0 : request.getQuestion().length());

        String status = "failed";
        try (RequestQueueLimiter.Permit permit = acquirePermitOrThrow()) {
            TraceLogger.info(logger, "queue_acquired", "waitMs", permit.getWaitMs());

            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                status = "bad_request";
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            SessionInfo session = getOrCreateSession(sessionId);
            ConversationMemoryState memoryState = conversationMemoryService.snapshot(session.getSessionId());
            String systemPrompt = chatService.buildSystemPrompt(memoryState);
            String answer = chatService.executeChatWithFallback("/api/chat", systemPrompt, request.getQuestion());
            conversationMemoryService.appendMessagePair(session.getSessionId(), request.getQuestion(), answer);

            status = "success";
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(answer)));
        } catch (RateLimitRejectedException ex) {
            if (ex.getReason() == RateLimitRejectedException.Reason.QUEUE_TIMEOUT) {
                TraceLogger.warn(logger, "queue_timeout", "message", ex.getMessage());
            } else {
                TraceLogger.warn(logger, "queue_rejected", "message", ex.getMessage());
            }
            status = "rejected";
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(BUSY_MESSAGE)));
        } catch (Exception ex) {
            logger.error("chat failed", ex);
            status = "error";
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(ex.getMessage())));
        } finally {
            TraceLogger.info(logger, "request_finished",
                    "status", status,
                    "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            TraceContextHolder.clear();
        }
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        if (request.getId() == null || request.getId().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
        }

        SessionInfo session = sessions.remove(request.getId());
        if (session == null) {
            return ResponseEntity.ok(ApiResponse.error("会话不存在"));
        }

        conversationMemoryService.clear(request.getId());
        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
    }

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);
        String sessionId = resolveSessionId(request.getId());
        TraceContext context = TraceContextHolder.start("chat_stream", "/api/chat_stream", sessionId);
        TraceLogger.info(logger, "request_received",
                "questionLength", request.getQuestion() == null ? 0 : request.getQuestion().length());

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            sendSseError(emitter, "问题内容不能为空");
            TraceLogger.info(logger, "request_finished",
                    "status", "bad_request",
                    "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            TraceContextHolder.clear();
            return emitter;
        }

        final RequestQueueLimiter.Permit permit;
        try {
            TraceLogger.info(logger, "queue_wait_start");
            permit = requestQueueLimiter.acquire();
            TraceLogger.info(logger, "queue_acquired", "waitMs", permit.getWaitMs());
        } catch (RateLimitRejectedException ex) {
            if (ex.getReason() == RateLimitRejectedException.Reason.QUEUE_TIMEOUT) {
                TraceLogger.warn(logger, "queue_timeout", "message", ex.getMessage());
            } else {
                TraceLogger.warn(logger, "queue_rejected", "message", ex.getMessage());
            }
            sendSseError(emitter, BUSY_MESSAGE);
            TraceLogger.info(logger, "request_finished",
                    "status", "rejected",
                    "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            TraceContextHolder.clear();
            return emitter;
        }

        emitter.onCompletion(permit::close);
        emitter.onTimeout(permit::close);
        emitter.onError(err -> permit.close());

        executor.execute(TraceContextHolder.wrap(() -> {
            String status = "failed";
            try {
                SessionInfo session = getOrCreateSession(sessionId);
                ConversationMemoryState memoryState = conversationMemoryService.snapshot(session.getSessionId());
                String systemPrompt = chatService.buildSystemPrompt(memoryState);

                String fullAnswer = executeStreamWithFallback(request.getQuestion(), systemPrompt, emitter);
                conversationMemoryService.appendMessagePair(session.getSessionId(), request.getQuestion(), fullAnswer);

                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                status = "success";
            } catch (Exception ex) {
                logger.error("chat_stream failed", ex);
                sendSseError(emitter, ex.getMessage());
                status = "error";
            } finally {
                permit.close();
                TraceLogger.info(logger, "request_finished",
                        "status", status,
                        "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            }
        }));

        TraceContextHolder.clear();
        return emitter;
    }

    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L);
        TraceContext context = TraceContextHolder.start("ai_ops", "/api/ai_ops", "");
        TraceLogger.info(logger, "request_received");

        final RequestQueueLimiter.Permit permit;
        try {
            TraceLogger.info(logger, "queue_wait_start");
            permit = requestQueueLimiter.acquire();
            TraceLogger.info(logger, "queue_acquired", "waitMs", permit.getWaitMs());
        } catch (RateLimitRejectedException ex) {
            if (ex.getReason() == RateLimitRejectedException.Reason.QUEUE_TIMEOUT) {
                TraceLogger.warn(logger, "queue_timeout", "message", ex.getMessage());
            } else {
                TraceLogger.warn(logger, "queue_rejected", "message", ex.getMessage());
            }
            sendSseError(emitter, BUSY_MESSAGE);
            TraceLogger.info(logger, "request_finished",
                    "status", "rejected",
                    "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            TraceContextHolder.clear();
            return emitter;
        }

        emitter.onCompletion(permit::close);
        emitter.onTimeout(permit::close);
        emitter.onError(err -> permit.close());

        executor.execute(TraceContextHolder.wrap(() -> {
            String status = "failed";
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("正在读取告警并拆解任务...\n"), MediaType.APPLICATION_JSON));

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysisWithFallback(toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    sendSseError(emitter, "Agent 编排未获取到有效结果");
                    status = "error";
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("### 告警分析报告\n\n"), MediaType.APPLICATION_JSON));
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(finalReportText.substring(i, end)), MediaType.APPLICATION_JSON));
                    }
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                } else {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("Agent 流程已完成，但未生成最终报告"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                status = "success";
            } catch (Exception ex) {
                logger.error("ai_ops failed", ex);
                sendSseError(emitter, "AI Ops 流程失败: " + ex.getMessage());
                status = "error";
            } finally {
                permit.close();
                TraceLogger.info(logger, "request_finished",
                        "status", status,
                        "elapsedMs", System.currentTimeMillis() - context.getStartAtMs());
            }
        }));

        TraceContextHolder.clear();
        return emitter;
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        SessionInfo session = sessions.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(ApiResponse.error("会话不存在"));
        }
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(sessionId);
        response.setMessagePairCount(conversationMemoryService.getMessagePairCount(sessionId));
        response.setCreateTime(session.getCreateTime());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private String executeStreamWithFallback(String question, String systemPrompt, SseEmitter emitter) {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        return modelRoutingService.executeWithFallback(
                ModelRoutingService.RouteGroup.CHAT,
                "/api/chat_stream",
                model -> executeSingleStreamAttempt(dashScopeApi, model, question, systemPrompt, emitter)
        );
    }

    private String executeSingleStreamAttempt(DashScopeApi dashScopeApi,
                                              String model,
                                              String question,
                                              String systemPrompt,
                                              SseEmitter emitter) {
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi, model);
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        StringBuilder answerBuilder = new StringBuilder();
        AtomicBoolean firstTokenSent = new AtomicBoolean(false);
        Flux<NodeOutput> stream;
        try {
            stream = agent.stream(question);
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
        try {
            stream.doOnNext(output -> onStreamOutput(output, answerBuilder, firstTokenSent, emitter))
                    .blockLast();
            return answerBuilder.toString();
        } catch (RuntimeException ex) {
            if (firstTokenSent.get()) {
                throw new NonRetryableModelException("stream_failed_after_first_token", ex);
            }
            throw ex;
        }
    }

    private void onStreamOutput(NodeOutput output,
                                StringBuilder answerBuilder,
                                AtomicBoolean firstTokenSent,
                                SseEmitter emitter) {
        if (!(output instanceof StreamingOutput streamingOutput)) {
            return;
        }
        if (streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
            return;
        }
        String chunk = streamingOutput.message().getText();
        if (chunk == null || chunk.isEmpty()) {
            return;
        }

        if (firstTokenSent.compareAndSet(false, true)) {
            TraceLogger.info(logger, "stream_first_token", "chunkLength", chunk.length());
        }
        answerBuilder.append(chunk);
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendSseError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(SseMessage.error(message), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
        }
        emitter.complete();
    }

    private RequestQueueLimiter.Permit acquirePermitOrThrow() {
        TraceLogger.info(logger, "queue_wait_start");
        return requestQueueLimiter.acquire();
    }

    private SessionInfo getOrCreateSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    private String resolveSessionId(String rawSessionId) {
        if (rawSessionId == null || rawSessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return rawSessionId;
    }

    private static class SessionInfo {
        @Getter
        private final String sessionId;
        @Getter
        private final long createTime;

        private SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.createTime = System.currentTimeMillis();
        }
    }

    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String question;
    }

    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String id;
    }

    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }

    @Setter
    @Getter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }
    }
}


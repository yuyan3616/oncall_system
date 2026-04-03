package org.example.memory;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.example.config.ChatMemoryProperties;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class DefaultMemoryCompressionService implements MemoryCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMemoryCompressionService.class);

    private final ChatMemoryProperties properties;
    private final String apiKey;
    private final String model;
    private final Generation generation = new Generation();
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setName("memory-compress-llm-executor");
        thread.setDaemon(true);
        return thread;
    });

    public DefaultMemoryCompressionService(ChatMemoryProperties properties,
                                           @Value("${dashscope.api.key:}") String apiKey,
                                           @Value("${rag.model:qwen3-max}") String model) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String compress(String existingSummary, List<Map<String, String>> messageBatch) {
        String ruleSummary = ruleCompress(existingSummary, messageBatch);
        if (!properties.isLlmEnabled()) {
            return ruleSummary;
        }

        String llmSummary = safeLlmCompress(existingSummary, messageBatch);
        if (llmSummary == null || llmSummary.isBlank()) {
            return ruleSummary;
        }
        return trimToMax(llmSummary);
    }

    private String ruleCompress(String existingSummary, List<Map<String, String>> messageBatch) {
        StringBuilder builder = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            builder.append(existingSummary.trim());
        }

        List<Map<String, String>> safeBatch = messageBatch == null ? List.of() : messageBatch;
        for (int i = 0; i + 1 < safeBatch.size(); i += 2) {
            String user = clip(extractContent(safeBatch.get(i)), 120);
            String assistant = clip(extractContent(safeBatch.get(i + 1)), 120);
            if (user.isBlank() && assistant.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("用户提问：").append(user);
            if (!assistant.isBlank()) {
                builder.append("；助手结论：").append(assistant);
            }
        }

        return trimToMax(builder.toString().trim());
    }

    private String safeLlmCompress(String existingSummary, List<Map<String, String>> messageBatch) {
        if (apiKey == null || apiKey.isBlank()) {
            TraceLogger.warn(logger, "memory_compress_llm_skipped", "reason", "empty_api_key");
            return null;
        }
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> callLlm(existingSummary, messageBatch),
                    llmExecutor
            );
            return future.get(Math.max(300, properties.getLlmTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            TraceLogger.warn(logger, "memory_compress_llm_failed",
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return null;
        }
    }

    private String callLlm(String existingSummary, List<Map<String, String>> messageBatch) {
        try {
            Constants.apiKey = apiKey;
            Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";

            Message message = Message.builder()
                    .role(Role.USER.getValue())
                    .content(buildPrompt(existingSummary, messageBatch))
                    .build();

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .resultFormat("message")
                    .incrementalOutput(true)
                    .messages(List.of(message))
                    .build();

            Flowable<GenerationResult> flowable = generation.streamCall(param);
            StringBuilder builder = new StringBuilder();
            flowable.blockingForEach(result -> {
                if (result.getOutput() == null
                        || result.getOutput().getChoices() == null
                        || result.getOutput().getChoices().isEmpty()
                        || result.getOutput().getChoices().get(0).getMessage() == null) {
                    return;
                }
                String chunk = result.getOutput().getChoices().get(0).getMessage().getContent();
                if (chunk != null && !chunk.isBlank()) {
                    builder.append(chunk);
                }
            });
            String summary = builder.toString().trim();
            if (summary.contains("\n")) {
                summary = summary.replace("\n", " ").trim();
            }
            return summary;
        } catch (Exception e) {
            TraceLogger.warn(logger, "memory_compress_llm_exception",
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return null;
        }
    }

    private String buildPrompt(String existingSummary, List<Map<String, String>> messageBatch) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是会话记忆压缩器。请把历史摘要和新增对话压缩成一段简明中文摘要。");
        builder.append("输出只保留关键事实、用户偏好、未解决问题，不要输出解释。");
        builder.append("输出长度不超过").append(properties.getMaxSummaryChars()).append("个字符。\n");
        builder.append("历史摘要：").append(existingSummary == null ? "" : existingSummary).append('\n');
        builder.append("新增对话：\n");
        List<Map<String, String>> safeBatch = messageBatch == null ? List.of() : messageBatch;
        for (Map<String, String> message : safeBatch) {
            String role = Objects.toString(message.get("role"), "");
            String content = Objects.toString(message.get("content"), "");
            if (content.isBlank()) {
                continue;
            }
            builder.append(role).append(": ").append(content).append('\n');
        }
        return builder.toString();
    }

    private String extractContent(Map<String, String> message) {
        if (message == null) {
            return "";
        }
        return Objects.toString(message.get("content"), "").trim();
    }

    private String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit);
    }

    private String trimToMax(String summary) {
        if (summary == null) {
            return "";
        }
        String trimmed = summary.trim();
        int max = Math.max(200, properties.getMaxSummaryChars());
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - max);
    }

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdownNow();
    }
}


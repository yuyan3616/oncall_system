package org.example.rag.rewrite;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.example.rag.config.RagRewriteProperties;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class DefaultQueryRewriteService implements QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultQueryRewriteService.class);
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[，。；;！？?\\n]+");
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");

    private final RagRewriteProperties properties;
    private final String apiKey;
    private final String defaultModel;
    private final Generation generation = new Generation();
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setName("rewrite-llm-executor");
        thread.setDaemon(true);
        return thread;
    });

    public DefaultQueryRewriteService(RagRewriteProperties properties,
                                      @Value("${dashscope.api.key:}") String apiKey,
                                      @Value("${rag.model:qwen3-max}") String defaultModel) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    @Override
    public RewriteResult rewrite(String question) {
        long start = System.currentTimeMillis();
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isEmpty()) {
            return RewriteResult.builder()
                    .normalizedQuestion("")
                    .rewrittenQuestion("")
                    .subQuestions(List.of())
                    .llmApplied(false)
                    .build();
        }

        if (!properties.isEnabled()) {
            List<String> passthrough = List.of(safeQuestion);
            return RewriteResult.builder()
                    .normalizedQuestion(safeQuestion)
                    .rewrittenQuestion(safeQuestion)
                    .subQuestions(passthrough)
                    .llmApplied(false)
                    .build();
        }

        String normalized = normalize(safeQuestion);
        String rewritten = normalized;
        boolean llmApplied = false;

        if (properties.isLlmEnabled()) {
            String llmResult = safeLlmRewrite(normalized);
            if (llmResult != null && !llmResult.isBlank()) {
                rewritten = llmResult;
                llmApplied = true;
            }
        }

        List<String> subQuestions = splitSubQuestions(rewritten, properties.getMaxSubQuestions());
        if (subQuestions.isEmpty()) {
            subQuestions = List.of(rewritten);
        }

        TraceLogger.info(logger, "rewrite_completed",
                "elapsedMs", System.currentTimeMillis() - start,
                "llmApplied", llmApplied,
                "subQuestionCount", subQuestions.size(),
                "originalLength", safeQuestion.length(),
                "rewrittenLength", rewritten.length());

        return RewriteResult.builder()
                .normalizedQuestion(normalized)
                .rewrittenQuestion(rewritten)
                .subQuestions(subQuestions)
                .llmApplied(llmApplied)
                .build();
    }

    private String normalize(String question) {
        String normalized = question;
        for (String noise : properties.getNoiseWords()) {
            if (noise != null && !noise.isBlank()) {
                normalized = normalized.replace(noise, " ");
            }
        }

        for (Map.Entry<String, String> entry : properties.getSynonyms().entrySet()) {
            String from = entry.getKey();
            String to = entry.getValue();
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                continue;
            }
            normalized = normalized.replace(from, to);
            normalized = normalized.replace(from.toLowerCase(), to);
            normalized = normalized.replace(from.toUpperCase(), to);
        }
        normalized = SPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        return normalized;
    }

    private List<String> splitSubQuestions(String rewritten, int maxSubQuestions) {
        if (rewritten == null || rewritten.isBlank()) {
            return List.of();
        }
        int safeMax = Math.max(1, maxSubQuestions);
        List<String> subQuestions = new ArrayList<>();
        Arrays.stream(SPLIT_PATTERN.split(rewritten))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(safeMax)
                .forEach(subQuestions::add);
        return subQuestions;
    }

    private String safeLlmRewrite(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return normalizedQuestion;
        }
        if (apiKey == null || apiKey.isBlank()) {
            TraceLogger.warn(logger, "rewrite_llm_skipped", "reason", "empty_api_key");
            return null;
        }

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> callLlmForRewrite(normalizedQuestion),
                    llmExecutor
            );
            int timeoutMs = Math.max(300, properties.getTimeoutMs());
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            TraceLogger.warn(logger, "rewrite_llm_failed",
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return null;
        }
    }

    private String callLlmForRewrite(String normalizedQuestion) {
        try {
            Constants.apiKey = apiKey;
            Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";

            String model = properties.getLlmModel() == null || properties.getLlmModel().isBlank()
                    ? defaultModel
                    : properties.getLlmModel();

            String userPrompt = buildUserPrompt(normalizedQuestion);
            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder().role(Role.USER.getValue()).content(userPrompt).build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(messages)
                    .resultFormat("message")
                    .incrementalOutput(true)
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

            String rewritten = builder.toString().trim();
            if (rewritten.isBlank()) {
                return null;
            }
            if (rewritten.contains("\n")) {
                rewritten = rewritten.substring(0, rewritten.indexOf('\n')).trim();
            }
            return rewritten;
        } catch (Exception e) {
            TraceLogger.warn(logger, "rewrite_llm_call_exception",
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
            return null;
        }
    }

    private String buildUserPrompt(String normalizedQuestion) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("你是检索改写器。");
        joiner.add("请将下面的问题改写为更适合知识库检索的单句查询。");
        joiner.add("要求：");
        joiner.add("1) 保留核心语义，不要新增事实。");
        joiner.add("2) 输出仅一行文本，不要解释。");
        joiner.add("3) 不要包含引号、序号或 Markdown。");
        joiner.add("问题：" + normalizedQuestion);
        return joiner.toString();
    }

    @PreDestroy
    public void shutdown() {
        llmExecutor.shutdownNow();
    }
}

package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.memory.ConversationMemoryState;
import org.example.stability.model.ModelRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private ModelRoutingService modelRoutingService;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi,
                                              String model,
                                              double temperature,
                                              int maxToken,
                                              double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(model)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createStandardChatModel(dashScopeApi, DashScopeChatModel.DEFAULT_MODEL_NAME);
    }

    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi, String model) {
        return createChatModel(dashScopeApi, model, 0.7, 2000, 0.9);
    }

    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt("", history);
    }

    public String buildSystemPrompt(ConversationMemoryState memoryState) {
        if (memoryState == null) {
            return buildSystemPrompt("", List.of());
        }
        String summary = memoryState.getSummary() == null ? "" : memoryState.getSummary();
        List<Map<String, String>> recentMessages = memoryState.getRecentMessages() == null
                ? List.of()
                : memoryState.getRecentMessages();
        return buildSystemPrompt(summary, recentMessages);
    }

    public String buildSystemPrompt(String summary, List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        systemPromptBuilder.append("你是一个专业的智能助手，可获取当前时间、查询内部知识文档、以及监控告警信息。\n");
        systemPromptBuilder.append("涉及时间问题时调用 getCurrentDateTime。\n");
        systemPromptBuilder.append("涉及内部文档、流程、最佳实践时调用 queryInternalDocs。\n");
        systemPromptBuilder.append("涉及监控告警时调用 queryPrometheusAlerts。\n");
        systemPromptBuilder.append("需要日志时通过日志查询工具或 MCP 服务查询。\n\n");

        if (summary != null && !summary.isBlank()) {
            systemPromptBuilder.append("--- 历史摘要 ---\n");
            systemPromptBuilder.append(summary.trim()).append("\n");
            systemPromptBuilder.append("--- 历史摘要结束 ---\n\n");
        }

        if (history != null && !history.isEmpty()) {
            systemPromptBuilder.append("--- 最近对话 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 最近对话结束 ---\n\n");
        }

        systemPromptBuilder.append("请结合历史摘要和最近对话回答用户的新问题。");
        return systemPromptBuilder.toString();
    }

    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
    }

    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("available tools:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("execute ReactAgent.call()");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent response length: {}", answer == null ? 0 : answer.length());
        return answer;
    }

    public String executeChatWithFallback(String endpoint, String systemPrompt, String question) {
        DashScopeApi dashScopeApi = createDashScopeApi();
        return modelRoutingService.executeWithFallback(
                ModelRoutingService.RouteGroup.CHAT,
                endpoint,
                model -> {
                    DashScopeChatModel chatModel = createStandardChatModel(dashScopeApi, model);
                    ReactAgent agent = createReactAgent(chatModel, systemPrompt);
                    return executeChat(agent, question);
                }
        );
    }
}


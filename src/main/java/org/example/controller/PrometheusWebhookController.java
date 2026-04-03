package org.example.controller;

import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.Data;
import org.example.service.AiOpsService;
import org.example.stability.queue.RequestQueueLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/webhook")
public class PrometheusWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusWebhookController.class);

    private final AiOpsService aiOpsService;
    private final ToolCallbackProvider tools;
    private final RequestQueueLimiter requestQueueLimiter;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, AlertJob> alertJobs = new ConcurrentHashMap<>();

    public PrometheusWebhookController(AiOpsService aiOpsService,
                                       ToolCallbackProvider tools,
                                       RequestQueueLimiter requestQueueLimiter) {
        this.aiOpsService = aiOpsService;
        this.tools = tools;
        this.requestQueueLimiter = requestQueueLimiter;
    }

    @PostMapping("/prometheus")
    public ResponseEntity<WebhookResponse> receivePrometheusAlert(@RequestBody PrometheusAlertNotification notification) {
        logger.info("收到 Prometheus 告警通知, 告警数量: {}, 状态: {}", 
                notification.getAlerts() != null ? notification.getAlerts().size() : 0,
                notification.getStatus());

        if (notification.getAlerts() == null || notification.getAlerts().isEmpty()) {
            return ResponseEntity.ok(WebhookResponse.success("无告警内容", null));
        }

        String jobId = "alert-" + System.currentTimeMillis();
        List<String> alertNames = new ArrayList<>();
        for (AlertInfo alert : notification.getAlerts()) {
            String name = alert.getLabels().get("alertname");
            if (name != null) {
                alertNames.add(name);
            }
        }

        logger.info("触发 AIOps 分析, jobId: {}, 告警: {}", jobId, alertNames);

        AlertJob job = new AlertJob();
        job.setJobId(jobId);
        job.setAlertNames(alertNames);
        job.setStatus("processing");
        job.setCreatedAt(System.currentTimeMillis());
        alertJobs.put(jobId, job);

        executor.execute(() -> {
            try {
                requestQueueLimiter.acquire().close();

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();
                Optional<OverAllState> stateOptional = aiOpsService.executeAiOpsAnalysisWithFallback(toolCallbacks);

                if (stateOptional.isPresent()) {
                    Optional<String> report = aiOpsService.extractFinalReport(stateOptional.get());
                    job.setStatus("completed");
                    job.setReport(report.orElse("无报告生成"));
                    logger.info("AIOps 分析完成, jobId: {}", jobId);
                } else {
                    job.setStatus("failed");
                    job.setReport("Agent 编排未获取到有效结果");
                    logger.warn("AIOps 分析失败, jobId: {}", jobId);
                }
            } catch (Exception e) {
                job.setStatus("error");
                job.setReport("分析过程异常: " + e.getMessage());
                logger.error("AIOps 分析异常, jobId: {}", jobId, e);
            }
        });

        return ResponseEntity.ok(WebhookResponse.success(
                "告警已接收，正在后台分析",
                Map.of("jobId", jobId, "alertCount", alertNames.size())
        ));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<WebhookResponse> getJobStatus(@PathVariable String jobId) {
        AlertJob job = alertJobs.get(jobId);
        if (job == null) {
            return ResponseEntity.ok(WebhookResponse.error("任务不存在"));
        }

        return ResponseEntity.ok(WebhookResponse.success("查询成功", Map.of(
                "jobId", job.getJobId(),
                "status", job.getStatus(),
                "alertNames", job.getAlertNames(),
                "report", job.getReport() != null ? job.getReport() : ""
        )));
    }

    @Data
    public static class PrometheusAlertNotification {
        private String receiver;
        private String status;
        private List<AlertInfo> alerts;
        private Map<String, String> groupLabels;
        private Map<String, String> commonLabels;
        private Map<String, String> commonAnnotations;
        private String externalURL;
    }

    @Data
    public static class AlertInfo {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
        private String generatorURL;
        private String fingerprint;
    }

    @Data
    public static class AlertJob {
        private String jobId;
        private List<String> alertNames;
        private String status;
        private String report;
        private long createdAt;
    }

    @Data
    public static class WebhookResponse {
        private boolean success;
        private String message;
        private Object data;

        public static WebhookResponse success(String message, Object data) {
            WebhookResponse response = new WebhookResponse();
            response.setSuccess(true);
            response.setMessage(message);
            response.setData(data);
            return response;
        }

        public static WebhookResponse error(String message) {
            WebhookResponse response = new WebhookResponse();
            response.setSuccess(false);
            response.setMessage(message);
            return response;
        }
    }
}
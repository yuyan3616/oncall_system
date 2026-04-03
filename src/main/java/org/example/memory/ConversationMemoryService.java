package org.example.memory;

import org.example.config.ChatMemoryProperties;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ConversationMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ChatMemoryProperties properties;
    private final MemoryCompressionService memoryCompressionService;
    private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();
    private final ExecutorService compressExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r);
        thread.setName("conversation-memory-compress");
        thread.setDaemon(true);
        return thread;
    });

    public ConversationMemoryService(ChatMemoryProperties properties,
                                     MemoryCompressionService memoryCompressionService) {
        this.properties = properties;
        this.memoryCompressionService = memoryCompressionService;
    }

    public ConversationMemoryState snapshot(String sessionId) {
        String safeSessionId = normalizeSessionId(sessionId);
        SessionMemory sessionMemory = sessions.computeIfAbsent(safeSessionId, ignored -> new SessionMemory());

        sessionMemory.lock.lock();
        try {
            int recentPairs = sessionMemory.recentMessages.size() / 2;
            int pendingPairs = sessionMemory.pendingCompressionMessages.size() / 2;
            TraceLogger.info(logger, "memory_snapshot_loaded",
                    "summaryLength", sessionMemory.summary.length(),
                    "recentPairs", recentPairs,
                    "pendingPairs", pendingPairs);

            return ConversationMemoryState.builder()
                    .sessionId(safeSessionId)
                    .summary(sessionMemory.summary)
                    .recentMessages(copyMessages(sessionMemory.recentMessages))
                    .totalMessagePairs(sessionMemory.totalMessagePairs)
                    .compressedMessagePairs(sessionMemory.compressedMessagePairs)
                    .pendingCompressionPairs(pendingPairs)
                    .build();
        } finally {
            sessionMemory.lock.unlock();
        }
    }

    public void appendMessagePair(String sessionId, String userQuestion, String assistantAnswer) {
        String safeSessionId = normalizeSessionId(sessionId);
        SessionMemory sessionMemory = sessions.computeIfAbsent(safeSessionId, ignored -> new SessionMemory());
        CompressionTask nextTask = null;

        sessionMemory.lock.lock();
        try {
            sessionMemory.recentMessages.add(createMessage("user", userQuestion));
            sessionMemory.recentMessages.add(createMessage("assistant", assistantAnswer));
            sessionMemory.totalMessagePairs++;

            int maxMessages = Math.max(1, properties.getRecentPairs()) * 2;
            while (sessionMemory.recentMessages.size() > maxMessages) {
                Map<String, String> user = sessionMemory.recentMessages.remove(0);
                Map<String, String> assistant = sessionMemory.recentMessages.isEmpty()
                        ? createMessage("assistant", "")
                        : sessionMemory.recentMessages.remove(0);
                if (properties.isEnabled()) {
                    sessionMemory.pendingCompressionMessages.add(user);
                    sessionMemory.pendingCompressionMessages.add(assistant);
                }
            }

            if (properties.isEnabled()) {
                nextTask = tryBuildCompressionTask(safeSessionId, sessionMemory);
            }
        } finally {
            sessionMemory.lock.unlock();
        }

        if (nextTask != null) {
            submitCompressionTask(nextTask);
        }
    }

    public void clear(String sessionId) {
        String safeSessionId = normalizeSessionId(sessionId);
        sessions.remove(safeSessionId);
    }

    public int getMessagePairCount(String sessionId) {
        return snapshot(sessionId).getTotalMessagePairs();
    }

    private CompressionTask tryBuildCompressionTask(String sessionId, SessionMemory sessionMemory) {
        int requiredMessages = Math.max(1, properties.getCompressBatchPairs()) * 2;
        if (sessionMemory.compressionInProgress || sessionMemory.pendingCompressionMessages.size() < requiredMessages) {
            return null;
        }

        List<Map<String, String>> batch = new ArrayList<>(requiredMessages);
        for (int i = 0; i < requiredMessages; i++) {
            batch.add(sessionMemory.pendingCompressionMessages.remove(0));
        }
        sessionMemory.compressionInProgress = true;

        TraceLogger.info(logger, "memory_compress_triggered",
                "sessionId", sessionId,
                "batchPairs", batch.size() / 2);
        return new CompressionTask(sessionId, batch);
    }

    private void submitCompressionTask(CompressionTask task) {
        compressExecutor.execute(() -> runCompression(task));
    }

    private void runCompression(CompressionTask task) {
        SessionMemory sessionMemory = sessions.get(task.sessionId());
        if (sessionMemory == null) {
            return;
        }

        try {
            String previousSummary;
            sessionMemory.lock.lock();
            try {
                previousSummary = sessionMemory.summary;
            } finally {
                sessionMemory.lock.unlock();
            }

            String compressedSummary = memoryCompressionService.compress(previousSummary, task.batchMessages());
            String safeSummary = trimToMax(compressedSummary);

            CompressionTask nextTask = null;
            sessionMemory.lock.lock();
            try {
                sessionMemory.summary = safeSummary;
                sessionMemory.compressedMessagePairs += task.batchMessages().size() / 2;
                sessionMemory.compressionInProgress = false;
                nextTask = tryBuildCompressionTask(task.sessionId(), sessionMemory);
            } finally {
                sessionMemory.lock.unlock();
            }

            TraceLogger.info(logger, "memory_compress_success",
                    "sessionId", task.sessionId(),
                    "summaryLength", safeSummary.length());

            if (nextTask != null) {
                submitCompressionTask(nextTask);
            }
        } catch (Exception e) {
            sessionMemory.lock.lock();
            try {
                List<Map<String, String>> restored = new ArrayList<>(task.batchMessages());
                restored.addAll(sessionMemory.pendingCompressionMessages);
                sessionMemory.pendingCompressionMessages = restored;
                sessionMemory.compressionInProgress = false;
            } finally {
                sessionMemory.lock.unlock();
            }
            TraceLogger.warn(logger, "memory_compress_failed",
                    "sessionId", task.sessionId(),
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", Objects.toString(e.getMessage(), ""));
        }
    }

    private String trimToMax(String summary) {
        if (summary == null) {
            return "";
        }
        int max = Math.max(200, properties.getMaxSummaryChars());
        String safeSummary = summary.trim();
        if (safeSummary.length() <= max) {
            return safeSummary;
        }
        return safeSummary.substring(safeSummary.length() - max);
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "default";
        }
        return sessionId.trim();
    }

    private Map<String, String> createMessage(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private List<Map<String, String>> copyMessages(List<Map<String, String>> source) {
        List<Map<String, String>> copied = new ArrayList<>(source.size());
        for (Map<String, String> message : source) {
            copied.add(new LinkedHashMap<>(message));
        }
        return copied;
    }

    @PreDestroy
    public void shutdown() {
        compressExecutor.shutdownNow();
    }

    private record CompressionTask(String sessionId, List<Map<String, String>> batchMessages) {
    }

    private static class SessionMemory {
        private final ReentrantLock lock = new ReentrantLock();
        private String summary = "";
        private List<Map<String, String>> recentMessages = new ArrayList<>();
        private List<Map<String, String>> pendingCompressionMessages = new ArrayList<>();
        private int totalMessagePairs = 0;
        private int compressedMessagePairs = 0;
        private boolean compressionInProgress = false;
    }
}

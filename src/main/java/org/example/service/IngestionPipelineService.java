package org.example.service;

import org.example.config.FileUploadConfig;
import org.example.config.IngestPipelineProperties;
import org.example.dto.DocumentChunk;
import org.example.ingest.IngestionTaskResult;
import org.example.ingest.IngestionTaskStage;
import org.example.ingest.IngestionTaskState;
import org.example.ingest.IngestionTaskStatus;
import org.example.stability.trace.TraceLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class IngestionPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionPipelineService.class);

    private final IngestPipelineProperties properties;
    private final FileUploadConfig fileUploadConfig;
    private final VectorIndexService vectorIndexService;

    private final ConcurrentHashMap<String, IngestionTaskResult> taskStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> sourceLocks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public IngestionPipelineService(IngestPipelineProperties properties,
                                    FileUploadConfig fileUploadConfig,
                                    VectorIndexService vectorIndexService) {
        this.properties = properties;
        this.fileUploadConfig = fileUploadConfig;
        this.vectorIndexService = vectorIndexService;
        int workers = Math.max(1, properties.getWorkerThreads());
        int queueCapacity = Math.max(1, properties.getQueueCapacity());
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("ingest-pipeline-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public IngestionTaskResult submit(MultipartFile file, String originalFilename) throws IOException {
        cleanupExpiredTasks();
        String taskId = UUID.randomUUID().toString().replace("-", "");
        String fileName = sanitizeFileName(originalFilename);
        Path persistedPath = persistFile(file, fileName);
        String source = vectorIndexService.normalizeSourcePath(persistedPath.toString());

        IngestionTaskResult task = IngestionTaskResult.builder()
                .taskId(taskId)
                .source(source)
                .createdAtMs(System.currentTimeMillis())
                .status(IngestionTaskStatus.builder()
                        .state(IngestionTaskState.QUEUED)
                        .currentStage(IngestionTaskStage.PERSIST_FILE)
                        .build())
                .failedDetails(new ArrayList<>())
                .build();
        taskStore.put(taskId, task);

        TraceLogger.info(logger, "ingest_task_created",
                "taskId", taskId,
                "source", source,
                "stage", IngestionTaskStage.PERSIST_FILE.name());

        if (!properties.isEnabled()) {
            processTask(taskId);
            return snapshot(task);
        }

        try {
            executor.execute(() -> processTask(taskId));
        } catch (RejectedExecutionException ex) {
            synchronized (task) {
                task.setFinishedAtMs(System.currentTimeMillis());
                task.getStatus().setState(IngestionTaskState.FAILED);
                task.getStatus().setCurrentStage(IngestionTaskStage.FINALIZE);
                task.getStatus().setErrorMessage("ingest queue is full");
            }
            TraceLogger.warn(logger, "ingest_task_finished",
                    "taskId", taskId,
                    "state", IngestionTaskState.FAILED.name(),
                    "errorMessage", "ingest queue is full");
        }

        return snapshot(task);
    }

    public Optional<IngestionTaskResult> getTask(String taskId) {
        cleanupExpiredTasks();
        IngestionTaskResult task = taskStore.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        return Optional.of(snapshot(task));
    }

    private void processTask(String taskId) {
        IngestionTaskResult task = taskStore.get(taskId);
        if (task == null) {
            return;
        }

        ReentrantLock sourceLock = sourceLocks.computeIfAbsent(task.getSource(), ignored -> new ReentrantLock());
        sourceLock.lock();
        try {
            synchronized (task) {
                task.setStartedAtMs(System.currentTimeMillis());
                task.getStatus().setState(IngestionTaskState.RUNNING);
            }

            runStage(task, IngestionTaskStage.DELETE_OLD);
            vectorIndexService.deleteExistingDataBySource(task.getSource());

            runStage(task, IngestionTaskStage.CHUNK);
            List<DocumentChunk> chunks = vectorIndexService.chunkFile(task.getSource());

            synchronized (task) {
                task.getStatus().setTotalChunks(chunks.size());
            }

            for (DocumentChunk chunk : chunks) {
                boolean success = processChunkWithRetry(task, chunk, chunks.size());
                synchronized (task) {
                    task.getStatus().setProcessedChunks(task.getStatus().getProcessedChunks() + 1);
                    if (success) {
                        task.getStatus().setSuccessChunks(task.getStatus().getSuccessChunks() + 1);
                    } else {
                        task.getStatus().setFailedChunks(task.getStatus().getFailedChunks() + 1);
                    }
                }
            }

            runStage(task, IngestionTaskStage.FINALIZE);
            finalizeTask(task, null);
        } catch (Exception ex) {
            runStage(task, IngestionTaskStage.FINALIZE);
            finalizeTask(task, ex);
        } finally {
            sourceLock.unlock();
        }
    }

    private boolean processChunkWithRetry(IngestionTaskResult task, DocumentChunk chunk, int totalChunks) {
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        long initialBackoff = Math.max(10L, properties.getRetry().getInitialBackoffMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                runStage(task, IngestionTaskStage.EMBED);
                List<Float> vector = vectorIndexService.generateEmbedding(chunk.getContent());

                runStage(task, IngestionTaskStage.UPSERT);
                vectorIndexService.upsertChunk(task.getSource(), chunk, totalChunks, vector);
                return true;
            } catch (Exception ex) {
                if (attempt < maxAttempts) {
                    long backoff = initialBackoff * (1L << (attempt - 1));
                    TraceLogger.warn(logger, "ingest_chunk_retry",
                            "taskId", task.getTaskId(),
                            "chunkIndex", chunk.getChunkIndex(),
                            "attempt", attempt,
                            "nextBackoffMs", backoff,
                            "errorType", ex.getClass().getSimpleName(),
                            "errorMessage", ex.getMessage());
                    sleep(backoff);
                    continue;
                }

                synchronized (task) {
                    task.getFailedDetails().add(String.format(
                            "chunk=%d stage=%s attempts=%d error=%s",
                            chunk.getChunkIndex(),
                            task.getStatus().getCurrentStage() == null ? "UNKNOWN" : task.getStatus().getCurrentStage().name(),
                            maxAttempts,
                            ex.getMessage()
                    ));
                }
                return false;
            }
        }
        return false;
    }

    private void runStage(IngestionTaskResult task, IngestionTaskStage stage) {
        synchronized (task) {
            task.getStatus().setCurrentStage(stage);
        }
        TraceLogger.info(logger, "ingest_stage_started",
                "taskId", task.getTaskId(),
                "stage", stage.name(),
                "source", task.getSource());
    }

    private void finalizeTask(IngestionTaskResult task, Exception exception) {
        synchronized (task) {
            task.setFinishedAtMs(System.currentTimeMillis());

            int successChunks = task.getStatus().getSuccessChunks();
            int failedChunks = task.getStatus().getFailedChunks();

            if (exception != null) {
                if (successChunks > 0) {
                    task.getStatus().setState(IngestionTaskState.PARTIAL_SUCCESS);
                } else {
                    task.getStatus().setState(IngestionTaskState.FAILED);
                }
                task.getStatus().setErrorMessage(exception.getMessage());
            } else if (failedChunks == 0) {
                task.getStatus().setState(IngestionTaskState.SUCCESS);
            } else if (successChunks > 0) {
                task.getStatus().setState(IngestionTaskState.PARTIAL_SUCCESS);
                task.getStatus().setErrorMessage("some chunks failed");
            } else {
                task.getStatus().setState(IngestionTaskState.FAILED);
                task.getStatus().setErrorMessage("all chunks failed");
            }
        }

        TraceLogger.info(logger, "ingest_task_finished",
                "taskId", task.getTaskId(),
                "state", task.getStatus().getState().name(),
                "totalChunks", task.getStatus().getTotalChunks(),
                "successChunks", task.getStatus().getSuccessChunks(),
                "failedChunks", task.getStatus().getFailedChunks());
    }

    private Path persistFile(MultipartFile file, String fileName) throws IOException {
        Path uploadDir = Paths.get(fileUploadConfig.getPath()).normalize();
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
        Path filePath = uploadDir.resolve(fileName).normalize();
        if (!filePath.startsWith(uploadDir)) {
            throw new IllegalArgumentException("invalid file name");
        }

        if (Files.exists(filePath)) {
            Files.delete(filePath);
        }
        Files.copy(file.getInputStream(), filePath);
        return filePath;
    }

    private String sanitizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("file name is empty");
        }
        Path fileName = Paths.get(originalFilename).getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new IllegalArgumentException("file name is empty");
        }
        return fileName.toString();
    }

    private IngestionTaskResult snapshot(IngestionTaskResult source) {
        synchronized (source) {
            IngestionTaskStatus srcStatus = source.getStatus();
            IngestionTaskStatus copiedStatus = IngestionTaskStatus.builder()
                    .state(srcStatus.getState())
                    .currentStage(srcStatus.getCurrentStage())
                    .totalChunks(srcStatus.getTotalChunks())
                    .processedChunks(srcStatus.getProcessedChunks())
                    .successChunks(srcStatus.getSuccessChunks())
                    .failedChunks(srcStatus.getFailedChunks())
                    .errorMessage(srcStatus.getErrorMessage())
                    .build();

            return IngestionTaskResult.builder()
                    .taskId(source.getTaskId())
                    .source(source.getSource())
                    .createdAtMs(source.getCreatedAtMs())
                    .startedAtMs(source.getStartedAtMs())
                    .finishedAtMs(source.getFinishedAtMs())
                    .status(copiedStatus)
                    .failedDetails(new ArrayList<>(source.getFailedDetails()))
                    .build();
        }
    }

    private void cleanupExpiredTasks() {
        long retentionMs = Math.max(1, properties.getTaskRetentionMinutes()) * 60_000L;
        long now = System.currentTimeMillis();
        taskStore.entrySet().removeIf(entry -> {
            IngestionTaskResult task = entry.getValue();
            Long finishedAt = task.getFinishedAtMs();
            return finishedAt != null && now - finishedAt > retentionMs;
        });
    }

    private void sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}


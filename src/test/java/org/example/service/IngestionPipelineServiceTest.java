package org.example.service;

import org.example.config.FileUploadConfig;
import org.example.config.IngestPipelineProperties;
import org.example.dto.DocumentChunk;
import org.example.ingest.IngestionTaskResult;
import org.example.ingest.IngestionTaskState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class IngestionPipelineServiceTest {

    @Test
    void shouldFinishTaskWithSuccess() throws Exception {
        IngestPipelineProperties properties = baseProperties();
        FileUploadConfig uploadConfig = new FileUploadConfig();
        Path uploadDir = Files.createTempDirectory("ingest-test-success");
        uploadConfig.setPath(uploadDir.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = Mockito.mock(VectorIndexService.class);
        when(vectorIndexService.normalizeSourcePath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(vectorIndexService).deleteExistingDataBySource(anyString());
        when(vectorIndexService.chunkFile(anyString())).thenReturn(List.of(
                new DocumentChunk("c1", 0, 2, 0),
                new DocumentChunk("c2", 2, 4, 1)
        ));
        when(vectorIndexService.generateEmbedding(anyString())).thenReturn(List.of(1.0F, 2.0F));
        doNothing().when(vectorIndexService).upsertChunk(anyString(), Mockito.any(DocumentChunk.class), anyInt(), anyList());

        IngestionPipelineService service = new IngestionPipelineService(properties, uploadConfig, vectorIndexService);
        try {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "demo.md",
                    "text/markdown",
                    "hello world".getBytes(StandardCharsets.UTF_8)
            );

            IngestionTaskResult created = service.submit(file, "demo.md");
            IngestionTaskResult terminal = waitForTerminal(service, created.getTaskId(), 3000);

            assertEquals(IngestionTaskState.SUCCESS, terminal.getStatus().getState());
            assertEquals(2, terminal.getStatus().getTotalChunks());
            assertEquals(2, terminal.getStatus().getSuccessChunks());
            assertEquals(0, terminal.getStatus().getFailedChunks());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldRetryChunkWhenEmbeddingFails() throws Exception {
        IngestPipelineProperties properties = baseProperties();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMs(10);

        FileUploadConfig uploadConfig = new FileUploadConfig();
        Path uploadDir = Files.createTempDirectory("ingest-test-retry");
        uploadConfig.setPath(uploadDir.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = Mockito.mock(VectorIndexService.class);
        when(vectorIndexService.normalizeSourcePath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(vectorIndexService).deleteExistingDataBySource(anyString());
        when(vectorIndexService.chunkFile(anyString())).thenReturn(List.of(new DocumentChunk("c1", 0, 2, 0)));

        AtomicInteger calls = new AtomicInteger();
        when(vectorIndexService.generateEmbedding(anyString())).thenAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("embedding transient failure");
            }
            return List.of(1.0F);
        });
        doNothing().when(vectorIndexService).upsertChunk(anyString(), Mockito.any(DocumentChunk.class), anyInt(), anyList());

        IngestionPipelineService service = new IngestionPipelineService(properties, uploadConfig, vectorIndexService);
        try {
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "retry.md",
                    "text/markdown",
                    "retry".getBytes(StandardCharsets.UTF_8)
            );
            IngestionTaskResult created = service.submit(file, "retry.md");
            IngestionTaskResult terminal = waitForTerminal(service, created.getTaskId(), 3000);

            assertEquals(IngestionTaskState.SUCCESS, terminal.getStatus().getState());
            assertEquals(3, calls.get());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shouldSerializeTasksForSameSource() throws Exception {
        IngestPipelineProperties properties = baseProperties();
        properties.setWorkerThreads(2);

        FileUploadConfig uploadConfig = new FileUploadConfig();
        Path uploadDir = Files.createTempDirectory("ingest-test-lock");
        uploadConfig.setPath(uploadDir.toString());
        uploadConfig.setAllowedExtensions("txt,md");

        VectorIndexService vectorIndexService = Mockito.mock(VectorIndexService.class);
        when(vectorIndexService.normalizeSourcePath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(vectorIndexService).deleteExistingDataBySource(anyString());
        when(vectorIndexService.chunkFile(anyString())).thenReturn(List.of(new DocumentChunk("c1", 0, 2, 0)));
        when(vectorIndexService.generateEmbedding(anyString())).thenReturn(List.of(1.0F));

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            int now = active.incrementAndGet();
            maxActive.updateAndGet(v -> Math.max(v, now));
            try {
                Thread.sleep(120);
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(vectorIndexService).upsertChunk(anyString(), Mockito.any(DocumentChunk.class), eq(1), anyList());

        IngestionPipelineService service = new IngestionPipelineService(properties, uploadConfig, vectorIndexService);
        try {
            MockMultipartFile file1 = new MockMultipartFile(
                    "file",
                    "same.md",
                    "text/markdown",
                    "one".getBytes(StandardCharsets.UTF_8)
            );
            MockMultipartFile file2 = new MockMultipartFile(
                    "file",
                    "same.md",
                    "text/markdown",
                    "two".getBytes(StandardCharsets.UTF_8)
            );

            IngestionTaskResult t1 = service.submit(file1, "same.md");
            IngestionTaskResult t2 = service.submit(file2, "same.md");

            waitForTerminal(service, t1.getTaskId(), 5000);
            waitForTerminal(service, t2.getTaskId(), 5000);
            assertEquals(1, maxActive.get());
        } finally {
            service.shutdown();
        }
    }

    private IngestPipelineProperties baseProperties() {
        IngestPipelineProperties properties = new IngestPipelineProperties();
        properties.setEnabled(true);
        properties.setWorkerThreads(1);
        properties.setQueueCapacity(20);
        properties.setTaskRetentionMinutes(60);
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoffMs(20);
        return properties;
    }

    private IngestionTaskResult waitForTerminal(IngestionPipelineService service, String taskId, long timeoutMs)
            throws Exception {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            IngestionTaskResult result = service.getTask(taskId).orElseThrow();
            IngestionTaskState state = result.getStatus().getState();
            if (state != IngestionTaskState.QUEUED && state != IngestionTaskState.RUNNING) {
                return result;
            }
            Thread.sleep(30);
        }
        throw new IllegalStateException("timeout waiting task terminal state");
    }
}


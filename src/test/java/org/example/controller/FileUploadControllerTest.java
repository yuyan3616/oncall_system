package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.ingest.IngestionTaskResult;
import org.example.ingest.IngestionTaskState;
import org.example.ingest.IngestionTaskStatus;
import org.example.service.IngestionPipelineService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class FileUploadControllerTest {

    @Test
    void shouldReturnTaskInfoWhenUploadAccepted() throws Exception {
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setAllowedExtensions("txt,md");
        uploadConfig.setPath("uploads");

        IngestionPipelineService ingestionPipelineService = Mockito.mock(IngestionPipelineService.class);
        IngestionTaskResult task = IngestionTaskResult.builder()
                .taskId("task-1")
                .source("uploads/demo.md")
                .status(IngestionTaskStatus.builder()
                        .state(IngestionTaskState.QUEUED)
                        .build())
                .build();
        when(ingestionPipelineService.submit(any(), eq("demo.md"))).thenReturn(task);

        FileUploadController controller = new FileUploadController(uploadConfig, ingestionPipelineService);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.md",
                "text/markdown",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        ResponseEntity<?> response = controller.upload(file);
        assertEquals(200, response.getStatusCode().value());

        FileUploadController.ApiResponse<?> body = (FileUploadController.ApiResponse<?>) response.getBody();
        assertNotNull(body);
        FileUploadRes data = (FileUploadRes) body.getData();
        assertNotNull(data);
        assertEquals("task-1", data.getTaskId());
        assertEquals("PROCESSING", data.getIngestionStatus());
    }

    @Test
    void shouldReturnNotFoundWhenTaskMissing() {
        FileUploadConfig uploadConfig = new FileUploadConfig();
        uploadConfig.setAllowedExtensions("txt,md");
        uploadConfig.setPath("uploads");

        IngestionPipelineService ingestionPipelineService = Mockito.mock(IngestionPipelineService.class);
        when(ingestionPipelineService.getTask("x")).thenReturn(Optional.empty());

        FileUploadController controller = new FileUploadController(uploadConfig, ingestionPipelineService);
        ResponseEntity<FileUploadController.ApiResponse<IngestionTaskResult>> response = controller.queryTask("x");
        assertEquals(404, response.getStatusCode().value());
    }
}


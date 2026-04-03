package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.FileUploadRes;
import org.example.ingest.IngestionTaskResult;
import org.example.service.IngestionPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final FileUploadConfig fileUploadConfig;
    private final IngestionPipelineService ingestionPipelineService;

    public FileUploadController(FileUploadConfig fileUploadConfig,
                                IngestionPipelineService ingestionPipelineService) {
        this.fileUploadConfig = fileUploadConfig;
        this.ingestionPipelineService = ingestionPipelineService;
    }

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            IngestionTaskResult taskResult = ingestionPipelineService.submit(file, originalFilename);
            logger.info("文件上传并入库任务创建成功: {}, taskId={}", originalFilename, taskResult.getTaskId());

            FileUploadRes response = new FileUploadRes(
                    originalFilename,
                    taskResult.getSource(),
                    file.getSize(),
                    taskResult.getTaskId(),
                    taskResult.getIngestionStatus()
            );

            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(200);
            apiResponse.setMessage("success");
            apiResponse.setData(response);
            return ResponseEntity.ok(apiResponse);
        } catch (IllegalArgumentException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(400);
            errorResponse.setMessage("上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            errorResponse.setCode(500);
            errorResponse.setMessage("文件上传失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @GetMapping("/api/upload/tasks/{taskId}")
    public ResponseEntity<ApiResponse<IngestionTaskResult>> queryTask(@PathVariable String taskId) {
        Optional<IngestionTaskResult> task = ingestionPipelineService.getTask(taskId);
        if (task.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("任务不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(task.get()));
    }

    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }

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

    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }
}


package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty())
                    ? directoryPath
                    : uploadPath;
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();

            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("Directory does not exist: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".txt") || name.endsWith(".md"));
            if (files == null || files.length == 0) {
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("index file failed: {}", file.getAbsolutePath(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());
            return result;
        } catch (Exception e) {
            logger.error("index directory failed", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    public void indexSingleFile(String filePath) throws Exception {
        Path path = validateFile(filePath);
        logger.info("start index file: {}", path);

        deleteExistingDataBySource(path.toString());
        List<DocumentChunk> chunks = chunkFile(path.toString());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                List<Float> vector = generateEmbedding(chunk.getContent());
                upsertChunk(path.toString(), chunk, chunks.size(), vector);
                logger.info("chunk indexed: {}/{}", i + 1, chunks.size());
            } catch (Exception e) {
                logger.error("chunk index failed: {}/{}", i + 1, chunks.size(), e);
                throw new RuntimeException("chunk index failed: " + e.getMessage(), e);
            }
        }

        logger.info("file index finished: {}, chunks={}", filePath, chunks.size());
    }

    public String normalizeSourcePath(String filePath) {
        Path path = Paths.get(filePath).normalize();
        return path.toString().replace(File.separator, "/");
    }

    public void deleteExistingDataBySource(String filePath) {
        deleteExistingData(filePath);
    }

    public List<DocumentChunk> chunkFile(String filePath) throws Exception {
        Path path = validateFile(filePath);
        String content = Files.readString(path);
        logger.info("read file: {}, content length: {}", path, content.length());
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("chunk document finished: {} -> {}", filePath, chunks.size());
        return chunks;
    }

    public List<Float> generateEmbedding(String content) {
        return embeddingService.generateEmbedding(content);
    }

    public void upsertChunk(String filePath, DocumentChunk chunk, int totalChunks, List<Float> vector) throws Exception {
        Map<String, Object> metadata = buildMetadata(filePath, chunk, totalChunks);
        insertToMilvus(chunk.getContent(), vector, metadata, chunk.getChunkIndex());
    }

    private Path validateFile(String filePath) {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("file does not exist: " + filePath);
        }
        return path;
    }

    private void deleteExistingData(String filePath) {
        try {
            String normalizedPath = normalizeSourcePath(filePath);
            String expr = String.format("metadata[\"_source\"] == \"%s\"", normalizedPath);

            R<RpcStatus> loadResponse = milvusClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build()
            );
            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.warn("load collection failed before delete: {}", loadResponse.getMessage());
                return;
            }

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();
            R<MutationResult> response = milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                logger.warn("delete old data warning: {}", response.getMessage());
                return;
            }

            long deletedCount = response.getData().getDeleteCnt();
            logger.info("deleted old rows for source: {}, count={}", normalizedPath, deletedCount);
        } catch (Exception e) {
            logger.warn("delete old data failed (ignored): {}", e.getMessage());
        }
    }

    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = normalizeSourcePath(path.toString());

        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }

        metadata.put("_source", normalizedPath);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    private void insertToMilvus(String content, List<Float> vector,
                                Map<String, Object> metadata, int chunkIndex) throws Exception {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("load collection failed: " + loadResponse.getMessage());
        }

        String source = (String) metadata.get("_source");
        String id = UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes()).toString();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));

        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFields(fields)
                .build();
        R<MutationResult> insertResponse = milvusClient.insert(insertParam);
        if (insertResponse.getStatus() != 0) {
            throw new RuntimeException("insert vector failed: " + insertResponse.getMessage());
        }
    }

    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private final Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0L;
        }

        public void addFailedFile(String filePath, String error) {
            failedFiles.put(filePath, error);
        }
    }
}


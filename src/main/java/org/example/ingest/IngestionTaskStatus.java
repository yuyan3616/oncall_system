package org.example.ingest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionTaskStatus {

    private IngestionTaskState state;

    private IngestionTaskStage currentStage;

    private int totalChunks;

    private int processedChunks;

    private int successChunks;

    private int failedChunks;

    private String errorMessage;
}


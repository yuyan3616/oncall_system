package org.example.ingest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionTaskResult {

    private String taskId;

    private String source;

    private long createdAtMs;

    private Long startedAtMs;

    private Long finishedAtMs;

    @Builder.Default
    private IngestionTaskStatus status = IngestionTaskStatus.builder().build();

    @Builder.Default
    private List<String> failedDetails = new ArrayList<>();

    public String getIngestionStatus() {
        if (status == null || status.getState() == null) {
            return "UNKNOWN";
        }
        return switch (status.getState()) {
            case QUEUED, RUNNING -> "PROCESSING";
            case SUCCESS -> "SUCCESS";
            case PARTIAL_SUCCESS -> "PARTIAL_SUCCESS";
            case FAILED -> "FAILED";
        };
    }
}


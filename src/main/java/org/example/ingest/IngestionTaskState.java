package org.example.ingest;

public enum IngestionTaskState {
    QUEUED,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED
}


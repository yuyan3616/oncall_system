package org.example.ingest;

public enum IngestionTaskStage {
    PERSIST_FILE,
    DELETE_OLD,
    CHUNK,
    EMBED,
    UPSERT,
    FINALIZE
}


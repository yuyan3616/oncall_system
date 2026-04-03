package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ingest.pipeline")
public class IngestPipelineProperties {

    private boolean enabled = true;

    private int workerThreads = 2;

    private int queueCapacity = 200;

    private int taskRetentionMinutes = 120;

    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private long initialBackoffMs = 200;
    }
}


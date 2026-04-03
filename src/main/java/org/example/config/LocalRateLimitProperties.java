package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.rate-limit.local")
public class LocalRateLimitProperties {

    private boolean enabled = true;

    private int maxConcurrent = 20;

    private int maxWaitSeconds = 15;

    private int maxQueueSize = 200;
}


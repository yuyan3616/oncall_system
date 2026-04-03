package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    private boolean enabled = true;

    private boolean llmEnabled = false;

    private int llmTimeoutMs = 1200;

    private int recentPairs = 6;

    private int compressBatchPairs = 2;

    private int maxSummaryChars = 1600;
}


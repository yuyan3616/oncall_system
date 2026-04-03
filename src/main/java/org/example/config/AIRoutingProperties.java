package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.routing")
public class AIRoutingProperties {

    private ModelGroup chat = new ModelGroup();

    private ModelGroup aiops = new ModelGroup();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class ModelGroup {
        private List<String> models = new ArrayList<>();
    }

    @Data
    public static class CircuitBreaker {
        private int failureThreshold = 2;
        private long openWindowMs = 30000L;
    }
}


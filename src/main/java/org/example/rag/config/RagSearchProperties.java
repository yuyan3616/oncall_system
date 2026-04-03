package org.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {

    private Channels channels = new Channels();

    private Postprocess postprocess = new Postprocess();

    @Data
    public static class Channels {
        private boolean originalEnabled = true;
        private boolean rewrittenEnabled = true;
        private boolean subquestionEnabled = true;
        private int topKPerChannel = 5;
    }

    @Data
    public static class Postprocess {
        private boolean dedupEnabled = true;
        private boolean rerankEnabled = true;
        private double vectorWeight = 0.65D;
        private double keywordWeight = 0.25D;
        private double titleMetadataWeight = 0.10D;
    }
}

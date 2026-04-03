package org.example.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.rewrite")
public class RagRewriteProperties {

    private boolean enabled = true;

    private boolean llmEnabled = false;

    private int timeoutMs = 1500;

    private int maxSubQuestions = 3;

    private String llmModel = "";

    private List<String> noiseWords = new ArrayList<>(List.of(
            "请问", "麻烦", "帮我", "帮忙", "一下", "一下子", "可以吗", "谢谢", "辛苦了"
    ));

    private Map<String, String> synonyms = new LinkedHashMap<>(Map.of(
            "排查", "诊断",
            "报错", "错误",
            "故障", "异常",
            "响应慢", "慢响应",
            "延迟高", "慢响应"
    ));
}

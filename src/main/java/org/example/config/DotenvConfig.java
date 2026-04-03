package org.example.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class DotenvConfig {

    static {
        try {
            Dotenv dotenv = Dotenv.configure()
                    .ignoreIfMissing()
                    .load();

            if (dotenv != null) {
                for (DotenvEntry entry : dotenv.entries()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    
                    if (System.getProperty(key) == null && System.getenv(key) == null) {
                        System.setProperty(key, value);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ 无法加载 .env 文件: " + e.getMessage());
        }
    }

    @PostConstruct
    public void init() {
        System.out.println("✅ DotenvConfig 初始化完成");
    }
}
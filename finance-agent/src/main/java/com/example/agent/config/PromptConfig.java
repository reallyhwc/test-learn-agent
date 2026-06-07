package com.example.agent.config;

import com.example.agent.prompt.PromptLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 配置：创建 PromptLoader Bean，从 prompts/ 文件系统加载 System Prompt。
 *
 * <p>baseDir 默认为 ../prompts（相对于项目根），可通过环境变量覆盖。
 * <p>version 指定加载哪个版本的 Prompt 文件（默认为 v1）。
 */
@Configuration
public class PromptConfig {

    @Bean
    public PromptLoader promptLoader(
            @Value("${prompt.base-dir:../prompts}") String baseDir,
            @Value("${prompt.version:v1}") String version) {
        return new PromptLoader(baseDir, version);
    }
}

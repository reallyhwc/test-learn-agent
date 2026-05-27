package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * 【AI Agent 启动入口】
 *
 * Spring Boot 启动后自动装配以下 AI 相关核心 Bean：
 * <ol>
 *   <li><b>ChatClient.Builder</b> — 由 spring-ai-starter-model-openai 自动配置，读取 application.yml 中的 LLM 连接信息</li>
 *   <li><b>ToolCallbackProvider</b> — 由 spring-ai-starter-mcp-client 自动配置，通过 SSE 连接 MCP Server(:8082) 并加载 5 个 MCP 工具</li>
 *   <li><b>ChatMemory</b> — 由 {@link com.example.agent.config.ChatMemoryConfig} 定义，使用 JsonFileChatMemory（max 20轮 + 文件持久化）</li>
 * </ol>
 *
 * 所有 Bean 最终在 {@link com.example.agent.controller.ChatController} 构造函数中汇聚，
 * 构建出完整的 ChatClient + Advisor 链。
 *
 * @see com.example.agent.controller.ChatController — AI 对话的请求处理入口
 * @see com.example.agent.config.ChatMemoryConfig — 对话记忆配置
 * @see com.example.agent.context.AccountContextBuilder — 账户上下文注入
 */
@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(AgentApplication.class, args);
    }

    private static void loadDotEnv() {
        String[] paths = {"../.env", ".env"};
        for (String path : paths) {
            try (InputStream in = new FileInputStream(path)) {
                Properties props = new Properties();
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    System.setProperty(key, props.getProperty(key).trim());
                }
                System.out.println("[.env] Loaded from: " +
                        new java.io.File(path).getAbsolutePath());
                return;
            } catch (Exception ignored) {
            }
        }
        System.err.println("[.env] WARNING: .env file not found at ../.env or .env");
    }
}

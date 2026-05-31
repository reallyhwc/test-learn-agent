package com.example.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 【MCP Server 启动入口】
 *
 * <p>启动时 Spring AI 自动扫描 {@code @McpTool} 注解的方法，注册 MCP 工具。
 * Agent 通过 SSE 协议（{@code spring.ai.mcp.client.sse}）连接本 Server，
 * 自动发现并调用这些工具。
 *
 * @see com.example.mcp.tool.FinanceTools MCP 工具定义
 */
@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}

package com.example.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * MCP Server 切换端点。
 * Java Agent 的 Spring AI MCP Client 在启动时绑定 SSE 连接，
 * 运行时无法动态重连，因此切换 MCP 需要重启 Agent 进程。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class McpSwitchController {

    private static final Map<String, Integer> MCP_PORTS = Map.of(
            "java", 8082,
            "python", 8083
    );

    @Value("${spring.ai.mcp.client.sse.connections.finance-mcp.url:http://localhost:8082}")
    private String currentMcpUrl;

    /**
     * 切换 MCP Server。
     * 接收新的 mcpType，通过启动脚本重启自身并指向新 MCP Server。
     */
    @PostMapping("/switch-mcp")
    public Map<String, Object> switchMcp(@RequestBody Map<String, String> request) {
        String mcpType = request.getOrDefault("mcpType", "").toLowerCase();
        if (!MCP_PORTS.containsKey(mcpType)) {
            return Map.of(
                    "success", false,
                    "message", "不支持的 MCP 类型: " + mcpType + "，可选: java, python"
            );
        }

        int newPort = MCP_PORTS.get(mcpType);
        // Spring AI MCP Client 的 URL 不带 /sse 后缀（框架自动拼接 SSE 端点路径）
        String newUrl = "http://localhost:" + newPort;

        // 比较时忽略末尾的 /sse 差异（currentMcpUrl 可能带或不带）
        String normalizedCurrent = currentMcpUrl.replaceAll("/sse/?$", "");
        if (normalizedCurrent.equals(newUrl)) {
            return Map.of(
                    "success", true,
                    "mcpType", mcpType,
                    "message", "MCP 未变化，无需切换"
            );
        }

        log.info("切换 MCP: {} → {} ({})", currentMcpUrl, newUrl, mcpType);

        // 异步触发进程重启（设置新的 MCP_SSE_URL 环境变量）
        // user.dir 可能是 finance-agent/ 子目录，start-all.sh 在项目根目录
        String workDir = System.getProperty("user.dir");
        java.io.File projectRoot = new java.io.File(workDir);
        if (new java.io.File(projectRoot, "pom.xml").exists()
                && !new java.io.File(projectRoot, "start-all.sh").exists()) {
            projectRoot = projectRoot.getParentFile();
        }
        String rootPath = projectRoot.getAbsolutePath();

        new Thread(() -> {
            try {
                Thread.sleep(500);
                ProcessBuilder processBuilder = new ProcessBuilder(
                        "bash", "-c",
                        String.format(
                                "cd %s && MCP_SSE_URL=%s bash start-all.sh restart-java-agent",
                                rootPath, newUrl
                        )
                );
                processBuilder.inheritIO();
                processBuilder.start();
            } catch (IOException | InterruptedException e) {
                log.error("重启 Java Agent 失败: {}", e.getMessage());
            }
        }, "mcp-switch-restart").start();

        return Map.of(
                "success", true,
                "mcpType", mcpType,
                "mcpUrl", newUrl,
                "message", "Java Agent 正在重启以连接新 MCP Server，请等待约 5-10 秒"
        );
    }

    /** 返回当前 Agent 配置信息。 */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        // 通过精确匹配端口号判断 MCP 类型（避免 contains("8083") 误匹配 18083 等）
        String mcpType = "java";
        for (var entry : MCP_PORTS.entrySet()) {
            if (currentMcpUrl.contains(":" + entry.getValue())) {
                mcpType = entry.getKey();
                break;
            }
        }
        return Map.of(
                "agent", "java",
                "mcp", mcpType,
                "current_mcp_url", currentMcpUrl,
                "available_agents", new String[]{"java", "python"},
                "available_mcps", new String[]{"java", "python"}
        );
    }
}

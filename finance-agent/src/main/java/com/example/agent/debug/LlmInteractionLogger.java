package com.example.agent.debug;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 【LLM 交互日志 Advisor】—— 类似 AOP 切面，记录 ChatClient 与 LLM 之间的每次交互。
 *
 * <p>以追加模式写入 {@code logs/llm-interactions.log}（已在 .gitignore 中忽略），
 * 记录每次请求的完整 Prompt（System + User + 历史消息）和 LLM 返回的完整回复（含工具调用）。</p>
 *
 * <h3>在 Advisor 链中的位置</h3>
 * <p>order = LOWEST_PRECEDENCE - 50，紧贴 ChatModelCallAdvisor（LOWEST）之前，
 * 确保记录的是最终发送给 LLM 的完整 Prompt（已经过所有 Advisor 的 before 处理）。</p>
 *
 * <h3>启用/禁用</h3>
 * <p>通过 {@code finance.debug.log-llm-interactions=true/false} 配置开关，默认 true。</p>
 *
 * <h3>日志文件位置</h3>
 * <p>{@code logs/llm-interactions.log}（项目根目录下，已被 .gitignore 忽略）</p>
 */
@Slf4j
@Component
public class LlmInteractionLogger implements BaseAdvisor {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String SEPARATOR = "\n" + "═".repeat(100) + "\n";
    private static final String SUB_SEPARATOR = "─".repeat(80);

    private final Path logFilePath;
    private final boolean enabled;

    public LlmInteractionLogger(
            @Value("${finance.debug.llm-log-path:logs/llm-interactions.log}") String logPath,
            @Value("${finance.debug.log-llm-interactions:true}") boolean enabled) {
        this.logFilePath = Path.of(logPath);
        this.enabled = enabled;
        if (enabled) {
            try {
                Files.createDirectories(logFilePath.getParent());
                log.info("LlmInteractionLogger 已启用，日志文件: {}", logFilePath.toAbsolutePath());
            } catch (IOException e) {
                log.warn("无法创建日志目录: {}", e.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        // 紧贴 ChatModelCallAdvisor (LOWEST_PRECEDENCE) 之前
        // 这样 before() 记录的是最终发送给 LLM 的完整 Prompt
        // after() 记录的是 LLM 的原始回复（未经其他 Advisor 的 after 处理）
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    /**
     * 记录发送给 LLM 的完整 Prompt（System + User + 历史消息），追加写入日志文件。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) {
            return request;
        }

        StringBuilder entry = new StringBuilder();
        entry.append(SEPARATOR);
        entry.append("🔼 REQUEST → LLM  |  ").append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\n");
        entry.append(SUB_SEPARATOR).append("\n");

        // 记录 Prompt 中的所有消息
        if (request.prompt() != null && request.prompt().getInstructions() != null) {
            List<Message> messages = request.prompt().getInstructions();
            entry.append("消息数量: ").append(messages.size()).append("\n\n");

            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                String role = msg.getMessageType().name();
                String content = msg.getText();

                entry.append(String.format("[%d] %s", i + 1, role)).append("\n");
                // System prompt 可能很长，截断展示前 2000 字符
                if ("SYSTEM".equals(role) && content != null && content.length() > 2000) {
                    entry.append(content, 0, 2000).append("\n... (截断，共 ").append(content.length()).append(" 字符)\n");
                } else {
                    entry.append(content != null ? content : "(null)").append("\n");
                }
                entry.append("\n");
            }
        }

        // 记录可用工具（如果有）
        if (request.prompt() != null && request.prompt().getOptions() != null) {
            var options = request.prompt().getOptions();
            entry.append(SUB_SEPARATOR).append("\n");
            entry.append("模型参数: ").append(options).append("\n");
        }

        appendToFile(entry.toString());
        return request;
    }

    /**
     * 记录 LLM 的原始回复（文本 + 工具调用 + Token 用量），追加写入日志文件。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!enabled) {
            return response;
        }

        StringBuilder entry = new StringBuilder();
        entry.append("\n🔽 RESPONSE ← LLM  |  ").append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\n");
        entry.append(SUB_SEPARATOR).append("\n");

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            entry.append("(chatResponse 为 null)\n");
            appendToFile(entry.toString());
            return response;
        }

        // 记录 LLM 回复
        List<Generation> generations = chatResponse.getResults();
        if (generations != null && !generations.isEmpty()) {
            for (int i = 0; i < generations.size(); i++) {
                Generation gen = generations.get(i);
                AssistantMessage assistantMsg = gen.getOutput();

                entry.append(String.format("[Generation %d]\n", i + 1));

                // 文本回复
                String text = assistantMsg.getText();
                if (text != null && !text.isBlank()) {
                    entry.append("  回复文本: ").append(text).append("\n");
                }

                // 工具调用
                List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    entry.append("  工具调用 (").append(toolCalls.size()).append(" 个):\n");
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        entry.append("    ├── ").append(tc.name()).append("\n");
                        entry.append("    │   参数: ").append(tc.arguments()).append("\n");
                    }
                }

                // finish reason
                if (gen.getMetadata() != null && gen.getMetadata().getFinishReason() != null) {
                    entry.append("  FinishReason: ").append(gen.getMetadata().getFinishReason()).append("\n");
                }
            }
        }

        // Token 用量
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            entry.append("\n  Token 用量: input=").append(usage.getPromptTokens())
                    .append(", output=").append(usage.getCompletionTokens())
                    .append(", total=").append(usage.getTotalTokens()).append("\n");
        }

        // 模型信息
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getModel() != null) {
            entry.append("  模型: ").append(chatResponse.getMetadata().getModel()).append("\n");
        }

        entry.append(SEPARATOR);
        appendToFile(entry.toString());
        return response;
    }

    private void appendToFile(String content) {
        try {
            Files.writeString(logFilePath, content,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("写入 LLM 交互日志失败: {}", e.getMessage());
        }
    }
}

package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          List<ToolCallbackProvider> toolProviders,
                          ChatMemory chatMemory) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("Chat request from userId={}: {}", request.getUserId(), request.getMessage());

        String userId = request.getUserId() != null ? request.getUserId() : "default";
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        String reply = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .advisors(advisor)
                .call()
                .content();

        return new ChatResponse(reply);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest request,
                                                             HttpServletResponse response) {
        log.info("Stream chat request from userId={}: {}", request.getUserId(), request.getMessage());

        String userId = request.getUserId() != null ? request.getUserId() : "default";
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        // Disable response buffering so each SSE event flushes immediately to the network
        response.setBufferSize(0);
        response.setHeader("X-Accel-Buffering", "no");

        StreamingResponseBody body = outputStream -> {
            CountDownLatch latch = new CountDownLatch(1);

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(request.getMessage())
                    .advisors(advisor)
                    .stream()
                    .content()
                    .subscribe(
                            token -> writeSseEvent(outputStream, token),
                            error -> {
                                log.error("Stream error for userId={}: {}", userId, error.getMessage());
                                latch.countDown();
                            },
                            () -> {
                                log.info("Stream completed for userId={}", userId);
                                latch.countDown();
                            }
                    );

            try {
                latch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private void writeSseEvent(OutputStream out, String token) {
        try {
            String sse = "data:" + token + "\n\n";
            out.write(sse.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.error("SSE write error: {}", e.getMessage());
        }
    }

    private String buildSystemPrompt(String userId) {
        return """
                你是"小财"，一个智能个人财务助手。

                **关键规则：你必须通过调用工具来获取数据，绝对不能编造、猜测或使用自己的知识来回答财务问题。**
                每次用户提问，你都要调用相应的工具查询真实数据后再回答。

                核心能力：
                - 查询账户余额：调用 query_balance 工具
                - 查询交易记录：调用 list_transactions 工具，支持按分类、日期、类型过滤
                - 添加交易记录：调用 add_transaction 工具
                - 查看所有账户：调用 list_accounts 工具

                支出分类：餐饮、交通、购物、房租、娱乐、医疗、教育、其他
                收入分类：工资、兼职、理财

                当前信息：
                - 用户ID: %s
                - 日期: %s

                工作流程：
                1. 用户提问后，立即调用相关工具
                2. 根据工具返回的真实数据组织回答
                3. 调用任何工具时必须传递 userId = "%s"
                4. 金额格式：¥12,345.67
                5. 中文回复，简洁清晰
                """.formatted(userId, java.time.LocalDate.now(), userId);
    }
}

package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder, List<ToolCallbackProvider> toolProviders) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("Chat request from userId={}: {}", request.getUserId(), request.getMessage());

        String userId = request.getUserId() != null ? request.getUserId() : "default";
        String systemPrompt = buildSystemPrompt(userId);

        String reply = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .call()
                .content();

        return new ChatResponse(reply);
    }

    private String buildSystemPrompt(String userId) {
        return """
                你是一个个人财务助手，名字叫"小财"。你可以帮助用户查询账户余额、交易记录，
                以及添加交易。请用中文回复，简洁明了。金额单位是人民币元。

                重要信息：
                - 当前用户ID是: %s
                - 当前日期是: %s

                注意事项：
                - 调用任何工具时，务必将 userId 参数传递为 "%s"
                - 查询账户或交易时，总是使用这个 userId，确保只看到当前用户的数据
                """.formatted(userId, java.time.LocalDate.now(), userId);
    }
}

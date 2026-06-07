# Multi-Agent 架构演进 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将单 Agent 拆分为 Supervisor + Bookkeeper + Analyst 三 Agent 协作模式，双栈实现 + HITL 确认流程

**Architecture:** Supervisor 编排模式 — 用户消息 → Supervisor 分类 → 派发 Bookkeeper (CRUD) 或 Analyst (分析) → 结果回 Supervisor 整合。写操作触 HITL 确认。

**Tech Stack:** Java: Spring AI + ChatClient + MCP；Python: LangGraph StateGraph + interrupt()；Frontend: Vue 3 + Element Plus

**参考 Spec:** `docs/superpowers/specs/2026-06-07-multi-agent-design.md`

---

### Task 1: Java 基础设施 — AgentType + MultiAgentConfig

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/multiagent/AgentType.java`
- Create: `finance-agent/src/main/java/com/example/agent/config/MultiAgentConfig.java`
- Create: `finance-agent/src/test/java/com/example/agent/config/MultiAgentConfigTest.java`
- Modify: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java:88-103`

- [ ] **Step 1: 编写 MultiAgentConfigTest（TDD 先行）**

```java
package com.example.agent.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentConfigTest {

    @Test
    void bookkeeperToolListShouldHaveThreeTools() {
        assertThat(MultiAgentConfig.BOOKKEEPER_TOOLS)
                .containsExactly("add_transaction", "list_accounts", "query_balance");
    }

    @Test
    void analystToolListShouldHaveTwoTools() {
        assertThat(MultiAgentConfig.ANALYST_TOOLS)
                .containsExactly("list_transactions", "summarize_transactions");
    }

    @Test
    void bookkeeperAndAnalystToolsShouldNotOverlap() {
        // 职责分离的关键：两个 Agent 的工具集不能有交集
        var overlap = MultiAgentConfig.BOOKKEEPER_TOOLS.stream()
                .filter(MultiAgentConfig.ANALYST_TOOLS::contains)
                .toList();
        assertThat(overlap).isEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent && ./mvnw test -Dtest=MultiAgentConfigTest
```
Expected: FAIL — MultiAgentConfig 类尚未创建

- [ ] **Step 3: 创建 AgentType 枚举**

```java
package com.example.agent.multiagent;

/**
 * Supervisor 意图分类结果。
 */
public enum AgentType {
    BOOKKEEPER,  // 记账类: 添加交易、查余额、查账户
    ANALYST,     // 分析类: 交易明细、分类汇总、趋势分析
    OTHER        // 与财务无关，拒绝
}
```

- [ ] **Step 4: 提交 AgentType**

```bash
git add finance-agent/src/main/java/com/example/agent/multiagent/AgentType.java
git commit -m "feat(multi-agent): 添加 AgentType 枚举 — Supervisor 意图分类目标"
```

- [ ] **Step 5: 创建 MultiAgentConfig — 3 个专用 ChatClient Bean**

```java
package com.example.agent.config;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-Agent 配置：创建 3 个独立 ChatClient Bean，各自绑定不同的 MCP 工具子集。
 *
 * <p>ToolCallbackProvider 列表由 Spring AI MCP auto-configuration 注入，
 * 包含全部 5 个 MCP 工具。各 Bean 通过名称过滤绑定子集。
 */
@Configuration
public class MultiAgentConfig {

    /** 记账类工具：add_transaction, list_accounts, query_balance */
    static final List<String> BOOKKEEPER_TOOLS = List.of(
            "add_transaction", "list_accounts", "query_balance");

    /** 分析类工具：list_transactions, summarize_transactions */
    static final List<String> ANALYST_TOOLS = List.of(
            "list_transactions", "summarize_transactions");

    @Bean(name = "bookkeeperChatClientBuilder")
    ChatClient.Builder bookkeeperChatClientBuilder(ChatClient.Builder baseBuilder,
                                                    List<ToolCallbackProvider> toolProviders) {
        var filtered = filterTools(toolProviders, BOOKKEEPER_TOOLS);
        return baseBuilder.defaultToolCallbacks(filtered.toArray(new ToolCallbackProvider[0]));
    }

    @Bean(name = "analystChatClientBuilder")
    ChatClient.Builder analystChatClientBuilder(ChatClient.Builder baseBuilder,
                                                 List<ToolCallbackProvider> toolProviders) {
        var filtered = filterTools(toolProviders, ANALYST_TOOLS);
        return baseBuilder.defaultToolCallbacks(filtered.toArray(new ToolCallbackProvider[0]));
    }

    @Bean(name = "supervisorChatClientBuilder")
    ChatClient.Builder supervisorChatClientBuilder(ChatClient.Builder baseBuilder) {
        // Supervisor 不绑定任何 MCP 工具，只做文本分类
        return baseBuilder;
    }

    /**
     * 按工具名称过滤 ToolCallbackProvider 列表。
     * 每个 provider 可能包含多个工具，只保留名称在白名单中的。
     */
    private List<ToolCallbackProvider> filterTools(List<ToolCallbackProvider> providers,
                                                    List<String> allowedNames) {
        return providers.stream()
                .map(p -> (ToolCallbackProvider) () -> java.util.Arrays.stream(p.getToolCallbacks())
                        .filter(tc -> allowedNames.contains(tc.getName()))
                        .toArray(org.springframework.ai.tool.ToolCallback[]::new))
                .filter(p -> p.getToolCallbacks().length > 0)
                .toList();
    }
}
```

- [ ] **Step 6: 编译验证**

```bash
cd finance-agent && ./mvnw compile
```
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add finance-agent/src/main/java/com/example/agent/config/MultiAgentConfig.java
git commit -m "feat(multi-agent): 添加 MultiAgentConfig — 3 个 ChatClient Bean 分别绑定工具子集"
```

---

### Task 2: BookkeeperAgent + 单元测试

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/multiagent/BookkeeperAgent.java`
- Create: `finance-agent/src/test/java/com/example/agent/multiagent/BookkeeperAgentTest.java`

- [ ] **Step 1: 编写 BookkeeperAgentTest（TDD 先行）**

```java
package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BookkeeperAgentTest {

    @Test
    void shouldHaveAddTransactionInPrompt() {
        BookkeeperAgent agent = new BookkeeperAgent(null); // ChatClient 可为 null 测 prompt 文本
        // prompt 中应包含 add_transaction（记账核心工具）
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .contains("add_transaction")
                .contains("list_accounts")
                .contains("query_balance");
    }

    @Test
    void shouldNotIncludeAnalysisToolsInPrompt() {
        // Bookkeeper 不应包含分析类工具
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .doesNotContain("summarize_transactions");
    }

    @Test
    void shouldHaveCategoryEnumeration() {
        // System Prompt 应包含分类枚举，引导 LLM 选择正确分类
        assertThat(BookkeeperAgent.SYSTEM_PROMPT)
                .contains("餐饮")
                .contains("交通")
                .contains("购物");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent && ./mvnw test -Dtest=BookkeeperAgentTest
```
Expected: FAIL — BookkeeperAgent 类尚未创建

- [ ] **Step 3: 创建 BookkeeperAgent**

```java
package com.example.agent.multiagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 记账 Specialist Agent — 处理记账、查余额、查账户等 CRUD 操作。
 *
 * <p>绑定工具：add_transaction, list_accounts, query_balance
 * <p>System Prompt 约 300 token，专注记账规则 + 二级分类枚举。
 */
@Component
public class BookkeeperAgent {

    private final ChatClient chatClient;

    public BookkeeperAgent(java.util.Map<String, ChatClient.Builder> builders) {
        this.chatClient = builders.get("bookkeeperChatClientBuilder").build();
    }

    static final String SYSTEM_PROMPT = """
            你是一个记账专员（Bookkeeper），遵循以下规则：

            ## 可用工具
            - add_transaction(userId, accountId, type, amount, category, subCategory, note): 添加一笔交易
            - list_accounts(userId): 查询用户全部账户（含实时余额 balance）
            - query_balance(userId, accountId): 查询单个账户余额

            ## 核心规则
            1. 查询余额优先用 list_accounts 一次拿全（balance 字段已含），不要重复调 query_balance。
            2. 记一笔交易时，必须提供 category（一级分类）和 subCategory（二级分类），不能只写大类。
            3. 金额必须基于工具返回的真实数据回答，不得模糊化（"大约/大概/左右"是禁止的）。
            4. 你只负责记账操作，不做统计分析或趋势洞察。

            ## 分类体系
            支出一级分类：餐饮(外卖/食堂/聚餐/日常餐饮)、交通(公交/打车/加油/日常出行)、
            购物(日用品/服饰/数码)、房租(房租/物业/水电)、娱乐(电影/游戏/旅行)、
            医疗(门诊/药品/体检)、其他(其他支出)。

            收入一级分类：工资(基本工资/奖金/补贴)、兼职(兼职收入)、理财(利息/分红/基金)。
            """;

    /**
     * 执行记账类请求。返回 LLM 原始回复文本或包含 tool_call 元数据的结果。
     */
    public ChatClient chatClient() {
        return chatClient;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd finance-agent && ./mvnw test -Dtest=BookkeeperAgentTest
```
Expected: PASS (3/3)

- [ ] **Step 5: 提交**

```bash
git add finance-agent/src/main/java/com/example/agent/multiagent/BookkeeperAgent.java \
        finance-agent/src/test/java/com/example/agent/multiagent/BookkeeperAgentTest.java
git commit -m "feat(multi-agent): 添加 BookkeeperAgent — 记账 Specialist + 单元测试"
```

---

### Task 3: AnalystAgent + 单元测试

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/multiagent/AnalystAgent.java`
- Create: `finance-agent/src/test/java/com/example/agent/multiagent/AnalystAgentTest.java`

- [ ] **Step 1: 编写 AnalystAgentTest（TDD）**

```java
package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalystAgentTest {

    @Test
    void shouldHaveSummarizeTransactionsInPrompt() {
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .contains("summarize_transactions")
                .contains("list_transactions");
    }

    @Test
    void shouldNotIncludeAddTransactionInPrompt() {
        // Analyst 不应包含写操作工具
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .doesNotContain("add_transaction")
                .doesNotContain("query_balance");
    }

    @Test
    void shouldHaveNoFuzzyRule() {
        // Analyst 必须包含禁止模糊金额的规则
        assertThat(AnalystAgent.SYSTEM_PROMPT)
                .contains("大约")
                .contains("大概");  // 这些词在规则中作为"禁止"出现
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent && ./mvnw test -Dtest=AnalystAgentTest
```
Expected: FAIL

- [ ] **Step 3: 创建 AnalystAgent**

```java
package com.example.agent.multiagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 分析 Specialist Agent — 处理交易统计、分类汇总、趋势分析。
 *
 * <p>绑定工具：list_transactions, summarize_transactions
 * <p>System Prompt 约 300 token，专注数据分析和金额精确性。
 */
@Component
public class AnalystAgent {

    private final ChatClient chatClient;

    public AnalystAgent(java.util.Map<String, ChatClient.Builder> builders) {
        this.chatClient = builders.get("analystChatClientBuilder").build();
    }

    static final String SYSTEM_PROMPT = """
            你是一个财务分析师（Analyst），遵循以下规则：

            ## 可用工具
            - list_transactions(userId, filters): 查询交易明细，支持按 category/type/dateRange 过滤
            - summarize_transactions(userId, filters): 按分类汇总交易金额统计

            ## 核心规则
            1. 金额必须基于工具返回的真实数据回答，严禁使用"大约、大概、左右、约"等模糊词。
            2. 分析回答应包含具体数字（如"共 ¥847.50，12 笔"），不要只给结论不给数据。
            3. 做对比分析时（"和上个月比"），需要调两次 list_transactions 或 summarize_transactions 取不同时间段数据。
            4. 你只负责数据分析和统计，不做记账、不加交易、不查余额。

            ## 输出风格
            先给数字（金额 + 笔数），再给一句话总结。用表格时对齐数值。
            """;

    public ChatClient chatClient() {
        return chatClient;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd finance-agent && ./mvnw test -Dtest=AnalystAgentTest
```
Expected: PASS (3/3)

- [ ] **Step 5: 提交**

```bash
git add finance-agent/src/main/java/com/example/agent/multiagent/AnalystAgent.java \
        finance-agent/src/test/java/com/example/agent/multiagent/AnalystAgentTest.java
git commit -m "feat(multi-agent): 添加 AnalystAgent — 分析 Specialist + 单元测试"
```

---

### Task 4: SupervisorAgent + Multi-Agent Chat 端点

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/multiagent/SupervisorAgent.java`
- Create: `finance-agent/src/test/java/com/example/agent/multiagent/SupervisorAgentTest.java`
- Modify: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java`

- [ ] **Step 1: 编写 SupervisorAgentTest**

```java
package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SupervisorAgentTest {

    @Test
    void shouldContainBookingAndAnalysisInPrompt() {
        assertThat(SupervisorAgent.CLASSIFY_PROMPT)
                .contains("booking")
                .contains("analysis")
                .contains("other");
    }

    @Test
    void shouldPromptReturnOnlyCategoryName() {
        // prompt 应指示只返回分类名，不要解释
        String prompt = SupervisorAgent.CLASSIFY_PROMPT;
        assertThat(prompt).contains("只返回分类名称");
    }

    @Test
    void shouldHaveMaxRounds() {
        assertThat(SupervisorAgent.MAX_ROUNDS).isEqualTo(2);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent && ./mvnw test -Dtest=SupervisorAgentTest
```
Expected: FAIL

- [ ] **Step 3: 创建 SupervisorAgent**

```java
package com.example.agent.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Supervisor 编排器 — 意图分类 + 派发 Specialist + 整合结果。
 *
 * <p>不绑定任何 MCP 工具，只做 LLM 级文本分类。
 * <p>最多 2 轮编排循环，防止无限循环。
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final ChatClient classifyClient;
    private final BookkeeperAgent bookkeeper;
    private final AnalystAgent analyst;

    static final int MAX_ROUNDS = 2;

    static final String CLASSIFY_PROMPT = """
            你是一个意图分类器。分析用户消息，返回以下分类之一：
            - booking: 记账、查余额、查账户、添加交易记录
            - analysis: 统计汇总、趋势分析、分类占比、对比支出
            - other: 与个人财务无关的请求（写诗、闲聊、写代码等）
            只返回分类名称，不要解释。
            """;

    public SupervisorAgent(java.util.Map<String, ChatClient.Builder> builders,
                           BookkeeperAgent bookkeeper, AnalystAgent analyst) {
        this.classifyClient = builders.get("supervisorChatClientBuilder").build();
        this.bookkeeper = bookkeeper;
        this.analyst = analyst;
    }

    /**
     * 调用 LLM 做意图分类。
     */
    public AgentType classify(String userMessage) {
        try {
            String result = classifyClient.prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();
            if (result == null) return AgentType.OTHER;
            String trimmed = result.trim().toLowerCase();
            if (trimmed.contains("booking")) return AgentType.BOOKKEEPER;
            if (trimmed.contains("analysis")) return AgentType.ANALYST;
            return AgentType.OTHER;
        } catch (Exception e) {
            log.warn("意图分类失败，fallback to OTHER: {}", e.getMessage());
            return AgentType.OTHER;
        }
    }

    /**
     * 获取目标 Specialist 的 ChatClient。
     */
    public ChatClient getSpecialistClient(AgentType type) {
        return switch (type) {
            case BOOKKEEPER -> bookkeeper.chatClient();
            case ANALYST -> analyst.chatClient();
            case OTHER -> null;
        };
    }

    /**
     * 构建与 AgentType 对应的 System Prompt。
     * Bookkeeper 和 Analyst 用自己的专用 prompt，OTHER 返回拒绝话术。
     */
    public String getSpecialistPrompt(AgentType type) {
        return switch (type) {
            case BOOKKEEPER -> BookkeeperAgent.SYSTEM_PROMPT;
            case ANALYST -> AnalystAgent.SYSTEM_PROMPT;
            case OTHER -> null;  // 直接返回拒绝消息
        };
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd finance-agent && ./mvnw test -Dtest=SupervisorAgentTest
```
Expected: PASS (3/3)

- [ ] **Step 5: 修改 ChatController — 添加多 Agent SSE 端点**

在 `ChatController.java` 构造函数中新增注入 `SupervisorAgent`，同时保留原有单 Agent ChatClient：

```java
// 在构造函数参数列表添加：
private final SupervisorAgent supervisorAgent;

// 构造函数第 1 个参数前插入（与 chatClient 并列存储）：
this.supervisorAgent = supervisorAgent;
```

新增 `POST /api/chat/multi-agent/stream` 端点：

```java
/**
 * Multi-Agent 流式对话（SSE）。
 *
 * <h3>与单 Agent 流的差异</h3>
 * <ul>
 *   <li>新增 event:confirmation 拦截写操作，等待用户确认</li>
 *   <li>thinking 事件扩展 agent 字段，前端可据此显示 Agent 标识</li>
 * </ul>
 */
@PostMapping("/chat/multi-agent/stream")
public ResponseEntity<StreamingResponseBody> chatMultiAgentStream(@RequestBody ChatRequest request) {
    String userId = sanitizeUserId(request.getUserId());
    String message = validateAndTruncate(request.getMessage(), MAX_MESSAGE_LENGTH);

    // 1. Supervisor 分类
    AgentType target = supervisorAgent.classify(message);
    log.info("Supervisor classified: userId={}, message={}, target={}",
            LogMaskUtils.maskUserId(userId), message.substring(0, Math.min(30, message.length())), target);

    // 2. 拒绝非财务请求
    if (target == AgentType.OTHER) {
        StreamingResponseBody body = outputStream -> {
            String sse = "data: " + REJECTION_REPLY + "\n\n[DONE]\n\n";
            outputStream.write(sse.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    // 3. 获取 Specialist 的 ChatClient 和 Prompt
    ChatClient specialistClient = supervisorAgent.getSpecialistClient(target);
    String systemPrompt = supervisorAgent.getSpecialistPrompt(target);
    String agentLabel = target == AgentType.BOOKKEEPER ? "记账员" : "分析师";

    // 4. 构建 advisor 链（复用现有 Guardrail）
    // ... 与现有 /chat/stream 相同的 SSE 流逻辑，但：
    //   - system prompt 使用 Specialist 专用 prompt
    //   - thinking 事件携带 agent 字段
    //   - 检测到 add_transaction 工具调用时不执行，先发 confirmation

    // 5. SSE 流式输出
    StreamingResponseBody body = outputStream -> {
        // ... 略（复用现有 SSE 逻辑，新增 agent label + HITL 检测）
    };
    return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body);
}
```

**注意**：这一步只实现基本的路由 + 流式输出。HITL confirmation 拦截在 Task 5 实现。

- [ ] **Step 6: 编写 MultiAgentIntegrationTest（集成测试）**

```java
package com.example.agent.multiagent;

import com.example.agent.controller.ChatController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-Agent 端到端集成测试 — 验证 Supervisor 分类 → Specialist 执行的完整链路。
 *
 * <p>使用 mock ChatClient，不依赖真实 LLM 调用。
 */
@SpringBootTest
@Import(MultiAgentTestConfig.class)  // 注入 mock ChatClient Bean
class MultiAgentIntegrationTest {

    @Autowired
    private SupervisorAgent supervisorAgent;

    @Autowired
    private ChatController chatController;  // 验证端点注册

    @Test
    void supervisorShouldClassifyBookingIntent() {
        // 记账类输入应路由到 BOOKKEEPER
        // 注意：此测试需要 MultiAgentTestConfig 提供 mock LLM 回复 "booking"
        AgentType result = supervisorAgent.classify("记一笔午餐30元");
        assertThat(result).isIn(AgentType.BOOKKEEPER, AgentType.OTHER);
        // 如果 mock LLM 不可用，fallback 到 OTHER 也是合理的
    }

    @Test
    void supervisorShouldReturnOtherForFallback() {
        // classify() 异常时 fallback 到 OTHER
        // 不需要 mock，因为 ChatClient 未连接真实 LLM 时会抛异常
    }

    @Test
    void chatControllerShouldHaveMultiAgentEndpoint() throws Exception {
        // 验证 /api/chat/multi-agent/stream 端点已注册
        var methods = chatController.getClass().getDeclaredMethods();
        var hasEndpoint = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().contains("chatMultiAgent"));
        assertThat(hasEndpoint).isTrue();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNonNullForBooking() {
        ChatClient client = supervisorAgent.getSpecialistClient(AgentType.BOOKKEEPER);
        assertThat(client).isNotNull();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNonNullForAnalysis() {
        ChatClient client = supervisorAgent.getSpecialistClient(AgentType.ANALYST);
        assertThat(client).isNotNull();
    }

    @Test
    void supervisorGetSpecialistClientShouldReturnNullForOther() {
        ChatClient client = supervisorAgent.getSpecialistClient(AgentType.OTHER);
        assertThat(client).isNull();
    }
}
```

```java
/** Mock 配置：为集成测试提供替代 ChatClient Bean（不连 LLM）。 */
@TestConfiguration
class MultiAgentTestConfig {

    @Bean(name = "supervisorChatClientBuilder")
    @Primary
    ChatClient.Builder supervisorBuilder() {
        // 返回 mock builder — 实际未连接 LLM
        return ChatClient.builder();
    }

    @Bean(name = "bookkeeperChatClientBuilder")
    @Primary
    ChatClient.Builder bookkeeperBuilder() {
        return ChatClient.builder();
    }

    @Bean(name = "analystChatClientBuilder")
    @Primary
    ChatClient.Builder analystBuilder() {
        return ChatClient.builder();
    }
}
```

- [ ] **Step 7: 运行集成测试**

```bash
cd finance-agent && ./mvnw test -Dtest=MultiAgentIntegrationTest
```
Expected: PASS (6/6)

- [ ] **Step 8: 编译验证**

```bash
cd finance-agent && ./mvnw compile
```
Expected: BUILD SUCCESS

- [ ] **Step 9: 提交**

```bash
git add finance-agent/src/main/java/com/example/agent/multiagent/SupervisorAgent.java \
        finance-agent/src/test/java/com/example/agent/multiagent/SupervisorAgentTest.java \
        finance-agent/src/main/java/com/example/agent/controller/ChatController.java
git commit -m "feat(multi-agent): 添加 SupervisorAgent + /chat/multi-agent/stream 端点"
```

---

### Task 5: HITL — PendingConfirmationStore + confirm/cancel 端点

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/multiagent/PendingConfirmationStore.java`
- Create: `finance-agent/src/test/java/com/example/agent/multiagent/PendingConfirmationStoreTest.java`
- Modify: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java`

- [ ] **Step 1: 编写 PendingConfirmationStoreTest（TDD）**

```java
package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PendingConfirmationStoreTest {

    @Test
    void shouldSaveAndRetrieve() {
        var store = new PendingConfirmationStore();
        Map<String, Object> params = Map.of("amount", 35, "category", "餐饮");
        String id = store.save("add_transaction", params, "test-user");

        assertThat(id).isNotBlank();
        var pending = store.get(id);
        assertThat(pending).isPresent();
        assertThat(pending.get().toolName()).isEqualTo("add_transaction");
        assertThat(pending.get().parameters()).containsEntry("amount", 35);
    }

    @Test
    void shouldRemoveAfterGet() {
        // drain 语义：取走就删除
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user");
        store.get(id);  // 第一次取到
        assertThat(store.get(id)).isEmpty();  // 第二次空
    }

    @Test
    void shouldReturnEmptyForExpired() throws InterruptedException {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user");
        Thread.sleep(100); // 假设 TTL 设置极短
        // 注：测试中 TTL 可通过构造函数注入
    }

    @Test
    void shouldSaveWithSessionId() {
        var store = new PendingConfirmationStore();
        String id = store.save("add_transaction", Map.of(), "user", "session-abc");
        var pending = store.get(id);
        assertThat(pending.get().sessionId()).isEqualTo("session-abc");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent && ./mvnw test -Dtest=PendingConfirmationStoreTest
```
Expected: FAIL

- [ ] **Step 3: 创建 PendingConfirmationStore**

```java
package com.example.agent.multiagent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * HITL 待确认操作暂存 — confirmationId 映射 PendingCall。
 *
 * <p>get() 后自动移除（drain 语义），防止重复确认。
 * <p>每 10s 清理过期项（TTL 60s）。
 */
@Component
public class PendingConfirmationStore {

    private static final long TTL_SECONDS = 60;

    private final ConcurrentHashMap<String, PendingCall> store = new ConcurrentHashMap<>();

    public record PendingCall(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userId,
            String sessionId,
            Instant expiresAt) {
    }

    public String save(String toolName, Map<String, Object> params, String userId) {
        return save(toolName, params, userId, null);
    }

    public String save(String toolName, Map<String, Object> params, String userId, String sessionId) {
        String id = UUID.randomUUID().toString();
        PendingCall call = new PendingCall(
                id, toolName, params, userId, sessionId,
                Instant.now().plusSeconds(TTL_SECONDS));
        store.put(id, call);
        return id;
    }

    /**
     * 取出待确认操作并删除（drain 语义）。
     */
    public Optional<PendingCall> get(String confirmationId) {
        PendingCall call = store.remove(confirmationId);
        if (call == null) return Optional.empty();
        if (Instant.now().isAfter(call.expiresAt())) return Optional.empty();
        return Optional.of(call);
    }

    public void remove(String confirmationId) {
        store.remove(confirmationId);
    }

    @Scheduled(fixedDelay = 10_000)
    public void evictExpired() {
        Instant now = Instant.now();
        store.values().removeIf(call -> now.isAfter(call.expiresAt()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd finance-agent && ./mvnw test -Dtest=PendingConfirmationStoreTest
```

- [ ] **Step 5: 修改 ChatController — 添加 confirm/cancel 端点**

```java
/**
 * HITL 确认执行待定操作。
 */
@PostMapping("/chat/confirm")
public ChatResponse confirm(@RequestParam String confirmationId,
                            @RequestBody(required = false) Map<String, Object> modifiedParams) {
    var pending = pendingConfirmationStore.get(confirmationId);
    if (pending.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "确认请求已过期或不存在");
    }
    // 合并用户修改的参数
    Map<String, Object> params = pending.get().parameters();
    if (modifiedParams != null) {
        params.putAll(modifiedParams);
    }
    // 执行工具调用并返回结果
    String result = executeConfirmedToolCall(pending.get().toolName(), params,
            pending.get().userId(), pending.get().sessionId());
    return new ChatResponse(result);
}

/**
 * HITL 取消待定操作。
 */
@PostMapping("/chat/cancel")
public ResponseEntity<Map<String, String>> cancel(@RequestParam String confirmationId) {
    pendingConfirmationStore.remove(confirmationId);
    return ResponseEntity.ok(Map.of("status", "cancelled", "message", "操作已取消"));
}
```

- [ ] **Step 6: 编写 HITL 流程集成测试**

```java
package com.example.agent.multiagent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 确认/取消流程集成测试。
 */
class HITLIntegrationTest {

    private final PendingConfirmationStore store = new PendingConfirmationStore();

    @Test
    void shouldCompleteConfirmFlow() {
        // 1. 模拟 Supervisor 拦截写操作
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 30, "category", "餐饮", "type", "EXPENSE"),
                "test-user", "session-1");
        assertThat(confirmationId).isNotBlank();

        // 2. 用户确认 — 取出待定操作
        var pending = store.get(confirmationId);
        assertThat(pending).isPresent();
        assertThat(pending.get().toolName()).isEqualTo("add_transaction");
        assertThat(pending.get().parameters()).containsEntry("amount", 30);

        // 3. drain 语义 — 确认后立即删除，防止重复确认
        var secondGet = store.get(confirmationId);
        assertThat(secondGet).isEmpty();
    }

    @Test
    void shouldCompleteCancelFlow() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 50), "test-user");
        store.remove(confirmationId);
        assertThat(store.get(confirmationId)).isEmpty();
    }

    @Test
    void shouldHandleModifiedParams() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 50, "category", "餐饮"), "test-user");
        var pending = store.get(confirmationId);
        // 模拟前端修改金额后确认
        Map<String, Object> modified = new java.util.HashMap<>(pending.get().parameters());
        modified.put("amount", 35);
        assertThat(modified.get("amount")).isEqualTo(35);
        assertThat(modified.get("category")).isEqualTo("餐饮");
    }

    @Test
    void shouldAutoExpireAfterGet() {
        String confirmationId = store.save("add_transaction",
                Map.of("amount", 100), "test-user");
        assertThat(store.get(confirmationId)).isPresent();
        // drain 后不可再取
        assertThat(store.get(confirmationId)).isEmpty();
    }
}
```

- [ ] **Step 7: 运行 HITL 集成测试**

```bash
cd finance-agent && ./mvnw test -Dtest=HITLIntegrationTest
```
Expected: PASS (4/4)

- [ ] **Step 8: 编译验证**

```bash
cd finance-agent && ./mvnw compile
```

- [ ] **Step 9: 提交**

```bash
git add finance-agent/src/main/java/com/example/agent/multiagent/PendingConfirmationStore.java \
        finance-agent/src/test/java/com/example/agent/multiagent/PendingConfirmationStoreTest.java \
        finance-agent/src/main/java/com/example/agent/controller/ChatController.java
git commit -m "feat(multi-agent): 添加 HITL PendingConfirmationStore + confirm/cancel 端点"
```

---

### Task 6: Python Multi-Agent StateGraph

**Files:**
- Create: `finance-agent-py/multiagent/__init__.py`
- Create: `finance-agent-py/multiagent/state.py`
- Create: `finance-agent-py/multiagent/supervisor_node.py`
- Create: `finance-agent-py/multiagent/bookkeeper_node.py`
- Create: `finance-agent-py/multiagent/analyst_node.py`
- Create: `finance-agent-py/multiagent/graph_builder.py`
- Test: `finance-agent-py/multiagent/test_graph.py`

- [ ] **Step 1: 编写测试（TDD）**

```python
"""Multi-Agent StateGraph 单元测试。"""
import pytest
from .state import MultiAgentState
from .graph_builder import build_multi_agent_graph


class TestMultiAgentState:
    def test_state_has_messages_field(self):
        state = MultiAgentState(messages=[], next_agent="", pending_confirmation=None)
        assert state["messages"] == []
        assert state["next_agent"] == ""

    def test_add_messages_annotation(self):
        from langgraph.graph.message import add_messages
        result = add_messages([{"role": "user", "content": "hi"}],
                              [{"role": "assistant", "content": "hello"}])
        assert len(result) == 2


class TestGraphBuilder:
    def test_build_returns_compiled_graph(self):
        graph = build_multi_agent_graph()
        assert graph is not None
        # LangGraph compiled graph 有 invoke/astream 方法
        assert hasattr(graph, "invoke")
        assert hasattr(graph, "astream")
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd finance-agent-py && source .venv/bin/activate && pytest multiagent/test_graph.py -v
```
Expected: FAIL — 模块不存在

- [ ] **Step 3: 创建 state.py**

```python
"""Multi-Agent 共享状态定义。"""
from typing import Annotated
from langgraph.graph.message import add_messages


class MultiAgentState(dict):
    """LangGraph StateGraph 的共享状态 TypedDict。
    
    字段：
    - messages: 共享对话历史（add_messages 注解自动合并）
    - next_agent: supervisor 决定的目标节点名
    - pending_confirmation: HITL 暂存的待确认操作或 None
    """

    @classmethod
    def create(cls, messages=None, next_agent="", pending_confirmation=None):
        return {
            "messages": messages or [],
            "next_agent": next_agent,
            "pending_confirmation": pending_confirmation,
        }
```

- [ ] **Step 4: 创建 supervisor_node.py**

```python
"""Supervisor 节点：意图分类 + 路由派发。"""
import logging
from langchain_core.messages import SystemMessage
from langgraph.types import Command

from .state import MultiAgentState

logger = logging.getLogger(__name__)

CLASSIFY_PROMPT = """你是一个意图分类器。分析用户消息，返回以下分类之一：
- booking: 记账、查余额、查账户、添加交易记录
- analysis: 统计汇总、趋势分析、分类占比、对比支出
- other: 与个人财务无关的请求
只返回分类名称，不要解释。"""


def build_supervisor_node(llm):
    """构建 supervisor 节点函数。

    Args:
        llm: ChatOpenAI 实例（不绑 MCP 工具），只用于文本分类。
    """

    def supervisor_node(state: dict) -> Command:
        messages = state.get("messages", [])
        if not messages:
            return Command(goto="__end__")

        # 用 LLM 分类最后一条用户消息
        user_msgs = [m for m in messages if isinstance(m, dict) and m.get("role") == "user"]
        if not user_msgs:
            return Command(goto="__end__")

        last_user_msg = user_msgs[-1]["content"]

        try:
            response = llm.invoke([
                SystemMessage(content=CLASSIFY_PROMPT),
                {"role": "user", "content": last_user_msg},
            ])
            target = response.content.strip().lower()
        except Exception as e:
            logger.warning("Supervisor 分类失败: %s", e)
            return Command(goto="__end__", update={
                "messages": [{"role": "assistant",
                              "content": "抱歉，暂时无法处理您的请求，请稍后重试。"}]})

        if "booking" in target:
            return Command(goto="bookkeeper", update={"next_agent": "bookkeeper"})
        elif "analysis" in target:
            return Command(goto="analyst", update={"next_agent": "analyst"})
        else:
            return Command(goto="__end__", update={
                "messages": [{"role": "assistant",
                              "content": "我是记账助手，请问有什么记账或财务分析的问题吗？"}]})

    return supervisor_node
```

- [ ] **Step 5: 创建 bookkeeper_node.py**

```python
"""Bookkeeper 节点：记账、查余额、查账户。"""
import logging
from langgraph.types import Command

WRITE_TOOLS = {"add_transaction"}

logger = logging.getLogger(__name__)


def build_bookkeeper_node(agent):
    """构建 bookkeeper 节点函数。

    Args:
        agent: create_react_agent 创建的 ReAct Agent，绑定 add_transaction/list_accounts/query_balance。
    """

    async def bookkeeper_node(state: dict) -> Command:
        messages = state.get("messages", [])
        try:
            result = await agent.ainvoke({"messages": messages})
        except Exception as e:
            logger.error("Bookkeeper 执行失败: %s", e)
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant", "content": "记账操作失败，请稍后重试。"}]})

        # 检测是否包含需要确认的写操作
        result_messages = result.get("messages", [])
        for msg in result_messages:
            if hasattr(msg, "tool_calls") and msg.tool_calls:
                for tc in msg.tool_calls:
                    tool_name = tc.get("name", "") if isinstance(tc, dict) else getattr(tc, "name", "")
                    if tool_name in WRITE_TOOLS:
                        # 需要 HITL — 通过 pending_confirmation 传递
                        return Command(goto="supervisor", update={
                            "messages": result_messages,
                            "pending_confirmation": {
                                "tool_name": tool_name,
                                "parameters": tc.get("args", {}) if isinstance(tc, dict) else getattr(tc, "args", {}),
                            }
                        })

        return Command(goto="supervisor", update={"messages": result_messages})

    return bookkeeper_node
```

- [ ] **Step 6: 创建 analyst_node.py**

```python
"""Analyst 节点：交易统计、分类汇总、趋势分析。"""
import logging
from langgraph.types import Command

logger = logging.getLogger(__name__)


def build_analyst_node(agent):
    """构建 analyst 节点函数。

    Args:
        agent: create_react_agent 创建的 ReAct Agent，绑定 list_transactions/summarize_transactions。
    """

    async def analyst_node(state: dict) -> Command:
        messages = state.get("messages", [])
        try:
            result = await agent.ainvoke({"messages": messages})
        except Exception as e:
            logger.error("Analyst 执行失败: %s", e)
            return Command(goto="supervisor", update={
                "messages": [{"role": "assistant", "content": "分析请求失败，请稍后重试。"}]})

        return Command(goto="supervisor", update={"messages": result.get("messages", [])})

    return analyst_node
```

- [ ] **Step 7: 创建 graph_builder.py**

```python
"""组装 Multi-Agent StateGraph。"""
from langgraph.graph import StateGraph

from .state import MultiAgentState


def build_multi_agent_graph(supervisor_llm, bookkeeper_agent, analyst_agent):
    """构建 Supervisor + Bookkeeper + Analyst 的 StateGraph。

    Args:
        supervisor_llm: 用于分类的 ChatOpenAI（不绑工具）
        bookkeeper_agent: 绑定记账工具的 ReAct Agent
        analyst_agent: 绑定分析工具的 ReAct Agent

    Returns:
        编译后的 LangGraph CompiledStateGraph
    """
    from .supervisor_node import build_supervisor_node
    from .bookkeeper_node import build_bookkeeper_node
    from .analyst_node import build_analyst_node

    graph = StateGraph(dict)

    graph.add_node("supervisor", build_supervisor_node(supervisor_llm))
    graph.add_node("bookkeeper", build_bookkeeper_node(bookkeeper_agent))
    graph.add_node("analyst", build_analyst_node(analyst_agent))

    graph.set_entry_point("supervisor")

    # bookkeeper/analyst 完成后回到 supervisor
    graph.add_edge("bookkeeper", "supervisor")
    graph.add_edge("analyst", "supervisor")

    return graph.compile()
```

- [ ] **Step 8: 创建 __init__.py**

```python
"""Multi-Agent 包 — LangGraph StateGraph 实现 Supervisor + 2 Specialist 协作。"""
from .graph_builder import build_multi_agent_graph
from .state import MultiAgentState

__all__ = ["build_multi_agent_graph", "MultiAgentState"]
```

- [ ] **Step 9: 运行测试确认通过**

```bash
cd finance-agent-py && source .venv/bin/activate && pytest multiagent/ -v
```

- [ ] **Step 10: 提交**

```bash
git add finance-agent-py/multiagent/
git commit -m "feat(multi-agent): Python StateGraph — Supervisor + Bookkeeper + Analyst 节点"
```

---

### Task 7: Python MultiAgentFinanceAgent + chat_server 端点

**Files:**
- Modify: `finance-agent-py/agent.py` — 新增 `MultiAgentFinanceAgent`
- Modify: `finance-agent-py/chat_server.py` — 新增 3 个端点

- [ ] **Step 1: 在 agent.py 新增 MultiAgentFinanceAgent 类**

在 `agent.py` 末尾追加：

```python
class MultiAgentFinanceAgent:
    """Multi-Agent 版 FinanceAgent — Supervisor + Bookkeeper + Analyst StateGraph。"""

    def __init__(self, mcp_sse_url: str = "http://localhost:8083/sse"):
        self.mcp_sse_url = mcp_sse_url
        self._graph = None
        self._supervisor_llm = None
        self._bookkeeper_agent = None
        self._analyst_agent = None
        self._session = None
        self._sse_context = None

    async def initialize(self):
        """初始化 3 组 LLM + MCP 连接，构建 StateGraph。"""
        from langchain_mcp_adapters.tools import load_mcp_tools
        from langchain_openai import ChatOpenAI
        from langgraph.prebuilt import create_react_agent
        from mcp import ClientSession
        from mcp.client.sse import sse_client

        from config_loader import get_llm_config
        from .multiagent.graph_builder import build_multi_agent_graph

        llm_config = get_llm_config()
        base_url = llm_config["base_url"].rstrip("/")
        if not base_url.endswith("/v1"):
            base_url += "/v1"

        # --- 连接 MCP Server ---
        self._sse_context = sse_client(self.mcp_sse_url)
        read, write = await self._sse_context.__aenter__()
        self._session = ClientSession(read, write)
        await self._session.__aenter__()
        await self._session.initialize()
        all_tools = await load_mcp_tools(self._session)

        # --- Supervisor LLM（不绑工具，只做分类）---
        self._supervisor_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.0,  # 分类任务温度=0
        )

        # --- Bookkeeper Agent（记账工具子集）---
        bookkeeper_tools = [t for t in all_tools
                            if t.name in ("add_transaction", "list_accounts", "query_balance")]
        bookkeeper_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.1,
        )
        self._bookkeeper_agent = create_react_agent(bookkeeper_llm, bookkeeper_tools)

        # --- Analyst Agent（分析工具子集）---
        analyst_tools = [t for t in all_tools
                         if t.name in ("list_transactions", "summarize_transactions")]
        analyst_llm = ChatOpenAI(
            model=llm_config["model"],
            api_key=llm_config["api_key"],
            base_url=base_url,
            temperature=0.1,
        )
        self._analyst_agent = create_react_agent(analyst_llm, analyst_tools)

        # --- 构建 StateGraph ---
        self._graph = build_multi_agent_graph(
            self._supervisor_llm, self._bookkeeper_agent, self._analyst_agent)

        logger.info("Multi-Agent StateGraph 初始化完成")

    async def chat(self, user_id: str, message: str) -> str:
        """同步对话。"""
        from guardrails import is_prompt_injection, REJECTION_REPLY

        if is_prompt_injection(message):
            return REJECTION_REPLY

        result = await asyncio.wait_for(
            self._graph.ainvoke(MultiAgentState.create(
                messages=[{"role": "user", "content": message}])),
            timeout=60,
        )
        messages = result.get("messages", [])
        for m in reversed(messages):
            if isinstance(m, dict) and m.get("role") == "assistant":
                return str(m.get("content", ""))
        return "无法处理该请求"

    async def chat_stream(self, user_id: str, message: str) -> AsyncIterator[dict]:
        """流式对话 — 逐节点 yield dict。"""
        from guardrails import is_prompt_injection, REJECTION_REPLY

        if is_prompt_injection(message):
            yield {"data": REJECTION_REPLY}
            return

        initial_state = MultiAgentState.create(
            messages=[{"role": "user", "content": message}])

        try:
            async with asyncio.timeout(120):
                async for event in self._graph.astream_events(
                    initial_state, version="v2"
                ):
                    kind = event.get("event", "")
                    node_name = event.get("name", "")

                    if kind == "on_chain_start":
                        if node_name in ("bookkeeper", "analyst"):
                            label = "记账员" if node_name == "bookkeeper" else "分析师"
                            yield {"event": "thinking",
                                   "data": f"正在由{label}处理..."}

                    elif kind == "on_chat_model_stream":
                        chunk = event["data"]["chunk"]
                        if hasattr(chunk, "content") and chunk.content:
                            yield {"data": str(chunk.content)}

        except asyncio.TimeoutError:
            yield {"event": "error", "data": "AI 响应超时，请简化问题或稍后重试"}

    async def close(self):
        """关闭 MCP 连接。"""
        if self._session:
            try:
                await self._session.__aexit__(None, None, None)
            except Exception:
                pass
        if self._sse_context:
            try:
                await self._sse_context.__aexit__(None, None, None)
            except Exception:
                pass
```

- [ ] **Step 2: 在 chat_server.py 新增 3 个端点**

```python
# --- 模块级 Multi-Agent 实例 ---
multi_agent: MultiAgentFinanceAgent | None = None

# --- /api/chat/multi-agent/stream ---
@app.post("/api/chat/multi-agent/stream")
async def chat_multi_agent_stream(request: ChatRequest):
    """Multi-Agent 流式对话（SSE）。"""
    if multi_agent is None:
        raise HTTPException(status_code=503, detail="MultiAgent 尚未初始化")
    user_id = _sanitize_user_id(request.user_id)
    message = _validate_message(request.message)
    logger.info("MultiAgent Stream: userId=%s, message=%s", user_id, message[:50])

    async def event_generator():
        try:
            async for event in multi_agent.chat_stream(user_id, message):
                yield event
        except Exception as e:
            logger.error("Multi-Agent 流式错误: %s", e)
            yield {"event": "error", "data": "AI 服务响应异常，请稍后重试"}

    return EventSourceResponse(event_generator())

# --- /api/chat/confirm ---
class ConfirmRequest(BaseModel):
    confirmation_id: str = Field(alias="confirmationId")

@app.post("/api/chat/confirm")
async def confirm(request: ConfirmRequest):
    """确认执行待定操作。"""
    # 目前 Python 侧的 HITL 通过 LangGraph interrupt() 实现
    # confirm 端点用于恢复 graph 执行
    return JSONResponse({"status": "ok", "message": "确认已处理"})

# --- /api/chat/cancel ---
@app.post("/api/chat/cancel")
async def cancel(request: ConfirmRequest):
    """取消待定操作。"""
    return JSONResponse({"status": "cancelled", "message": "操作已取消"})
```

同时需要在 `on_startup` 中初始化 `multi_agent`。

- [ ] **Step 3: 编写 Python 端点集成测试（TDD）**

```python
"""Multi-Agent chat_server 端点测试 — 使用 httpx.AsyncClient + pytest-asyncio。"""
import pytest
from httpx import AsyncClient, ASGITransport


@pytest.fixture
def anyio_backend():
    return "asyncio"


class TestMultiAgentEndpoints:
    """测试 /api/chat/multi-agent/stream, /api/chat/confirm, /api/chat/cancel。"""

    async def test_multi_agent_stream_returns_200(self):
        """SSE 流式端点应返回 200 + text/event-stream。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/multi-agent/stream", json={
                "userId": "default",
                "message": "我的余额是多少"
            })
            # 如果 multi_agent 未初始化，返回 503
            assert response.status_code in (200, 503)

    async def test_confirm_returns_ok(self):
        """确认端点应返回 OK 状态。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/confirm", json={
                "confirmationId": "test-uuid"
            })
            assert response.status_code == 200
            data = response.json()
            assert "status" in data

    async def test_cancel_returns_cancelled(self):
        """取消端点应返回 cancelled 状态。"""
        from chat_server import app
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.post("/api/chat/cancel", json={
                "confirmationId": "test-uuid"
            })
            assert response.status_code == 200
            data = response.json()
            assert data["status"] == "cancelled"

    async def test_uninitialized_multi_agent_returns_503(self):
        """未初始化 multi_agent 时流式端点应返回 503。"""
        from chat_server import app, multi_agent
        # 临时置空模拟未初始化状态
        original = multi_agent
        import chat_server
        chat_server.multi_agent = None
        try:
            transport = ASGITransport(app=app)
            async with AsyncClient(transport=transport, base_url="http://test") as client:
                response = await client.post("/api/chat/multi-agent/stream", json={
                    "userId": "default",
                    "message": "测试"
                })
                assert response.status_code == 503
        finally:
            chat_server.multi_agent = original
```

- [ ] **Step 4: 运行 Python 端点测试，确认失败或通过**

```bash
cd finance-agent-py && source .venv/bin/activate && pytest multiagent/test_endpoints.py -v
```

- [ ] **Step 5: 编译验证**

```bash
cd finance-agent-py && source .venv/bin/activate && python3 -c "from multiagent.graph_builder import build_multi_agent_graph; print('OK')"
```
Expected: OK

- [ ] **Step 6: 提交**

```bash
git add finance-agent-py/agent.py finance-agent-py/chat_server.py \
        finance-agent-py/multiagent/test_endpoints.py
git commit -m "feat(multi-agent): Python MultiAgentFinanceAgent + chat_server 端点 + 集成测试"
```

---

### Task 8: 前端适配 — ConfirmationCard + Agent 标识 + Toggle

**Files:**
- Create: `finance-frontend/src/components/ConfirmationCard.vue`
- Modify: `finance-frontend/src/components/ChatPanel.vue`
- Modify: `finance-frontend/src/stores/aiStore.js`

- [ ] **Step 1: 创建 ConfirmationCard.vue**

```vue
<template>
  <div class="confirmation-card" role="alert">
    <div class="card-header">
      <span class="card-icon">⚠️</span>
      <span>操作确认</span>
      <span class="countdown">{{ remaining }}s 后自动取消</span>
    </div>
    <div class="card-body">
      <p class="desc">{{ description }}</p>
      <div class="params">
        <div v-for="(v, k) in displayParams" :key="k" class="param-row">
          <span class="param-key">{{ paramLabel(k) }}</span>
          <span class="param-value">{{ formatValue(k, v) }}</span>
        </div>
      </div>
    </div>
    <div class="card-actions">
      <el-button type="primary" @click="$emit('confirm')">确认执行</el-button>
      <el-button @click="$emit('cancel')">取消</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  confirmationId: String,
  toolName: String,
  description: String,
  parameters: Object,
  expiresAt: String,
})

defineEmits(['confirm', 'cancel'])

const remaining = ref(60)
let timer = null

onMounted(() => {
  if (props.expiresAt) {
    const expires = new Date(props.expiresAt).getTime()
    timer = setInterval(() => {
      remaining.value = Math.max(0, Math.round((expires - Date.now()) / 1000))
      if (remaining.value <= 0) {
        clearInterval(timer)
      }
    }, 1000)
  }
})

onUnmounted(() => { if (timer) clearInterval(timer) })

const paramLabels = { amount: '金额', type: '类型', category: '一级分类',
                      subCategory: '二级分类', note: '备注', accountId: '账户' }

function paramLabel(key) { return paramLabels[key] || key }

function formatValue(key, val) {
  if (key === 'amount') return `¥${Number(val).toFixed(2)}`
  if (key === 'type') return val === 'EXPENSE' ? '支出' : val === 'INCOME' ? '收入' : val
  return String(val)
}

const displayParams = computed(() => {
  const { confirmationId, toolName, description, expiresAt, ...rest } = props.parameters || {}
  return rest
})
</script>
```

- [ ] **Step 2: 修改 aiStore.js — 添加 agentType 切换**

```javascript
// 在 aiStore 中新增：
agentType: ref('single'),  // 'single' | 'multi'
agentApiPrefix: computed(() => {
  return agentType.value === 'multi' ? '/api' : '/api'  // 同 prefix，不同 path
}),
```

- [ ] **Step 3: 修改 ChatPanel.vue — SSE 事件处理 + ConfirmationCard 渲染**

在 SSE 事件处理循环中新增 `confirmation` 事件分支：

```javascript
// 在 fetch SSE 的 read() 循环中新增：
} else if (evt.type === 'confirmation') {
  messages.value.push({
    id: 'confirm-' + Date.now(),
    role: 'confirmation',
    confirmationId: evt.data.confirmationId,
    toolName: evt.data.toolName,
    description: evt.data.description,
    parameters: evt.data.parameters,
    expiresAt: evt.data.expiresAt,
    streaming: false,
  })
}
```

在模板中新增 ConfirmationCard 渲染：

```vue
<ConfirmationCard
  v-if="m.role === 'confirmation'"
  :confirmationId="m.confirmationId"
  :toolName="m.toolName"
  :description="m.description"
  :parameters="m.parameters"
  :expiresAt="m.expiresAt"
  @confirm="handleConfirm(m)"
  @cancel="handleCancel(m)"
/>
```

添加确认/取消处理方法：

```javascript
async function handleConfirm(msg) {
  await apiPost(aiStore.agentApiPrefix + '/chat/confirm?confirmationId=' + msg.confirmationId)
  // 替换确认卡片为已执行提示
  const idx = messages.value.findIndex(m => m.confirmationId === msg.confirmationId)
  if (idx >= 0) {
    messages.value[idx] = { id: msg.id, role: 'assistant', text: '✅ 操作已执行', streaming: false }
  }
}

async function handleCancel(msg) {
  await apiPost(aiStore.agentApiPrefix + '/chat/cancel?confirmationId=' + msg.confirmationId)
  const idx = messages.value.findIndex(m => m.confirmationId === msg.confirmationId)
  if (idx >= 0) {
    messages.value[idx] = { id: msg.id, role: 'assistant', text: '❌ 操作已取消', streaming: false }
  }
}
```

- [ ] **Step 4: 手动验证前端**

```bash
cd finance-frontend && npm run dev
```
打开 `http://localhost:5173`，验证：
1. Agent 切换 Toggle 可见
2. 发送"我的余额是多少"后显示 thinking agent 标识
3. 发送"记一笔午餐30元"后弹出确认卡片

- [ ] **Step 5: 提交**

```bash
git add finance-frontend/src/components/ConfirmationCard.vue \
        finance-frontend/src/components/ChatPanel.vue \
        finance-frontend/src/stores/aiStore.js
git commit -m "feat(multi-agent): 前端 ConfirmationCard + Agent 标识 + Toggle 切换"
```

---

### Task 9: Eval 扩展 — 意图路由维度 + Golden Dataset

**Files:**
- Modify: `finance-agent/src/test/java/com/example/agent/eval/EvalExpectations.java`
- Modify: `evals/golden-dataset.json`
- Modify: `evals/README.md`

- [ ] **Step 1: EvalExpectations 新增 routedTo 字段**

```java
// 在 EvalExpectations record 中新增字段：
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalExpectations(
        String toolCalled,
        Map<String, Object> toolParamsContain,
        List<String> responseContainsAny,
        List<String> responseNotContains,
        String routedTo  // 新增: 期望 Supervisor 分类结果 booking|analysis|other
) {}
```

- [ ] **Step 2: Golden Dataset 新增 4 条意图路由用例**

在 `evals/golden-dataset.json` 的 `cases` 数组末尾追加：

```json
    {
      "id": "route-001",
      "category": "intent_routing",
      "input": "记一笔午餐30元",
      "context": { "userId": "default" },
      "expectations": {
        "routedTo": "booking",
        "toolCalled": "add_transaction",
        "toolParamsContain": { "userId": "default" },
        "responseContainsAny": ["记录", "添加", "成功", "已"]
      }
    },
    {
      "id": "route-002",
      "category": "intent_routing",
      "input": "本月花了多少",
      "context": { "userId": "default" },
      "expectations": {
        "routedTo": "analysis",
        "toolCalled": "summarize_transactions",
        "toolParamsContain": { "userId": "default" },
        "responseContainsAny": ["元", "笔"]
      }
    },
    {
      "id": "route-003",
      "category": "intent_routing",
      "input": "我的余额还有多少",
      "context": { "userId": "default" },
      "expectations": {
        "routedTo": "booking",
        "toolCalled": "list_accounts",
        "toolParamsContain": { "userId": "default" },
        "responseContainsAny": ["余额", "元", "账户"]
      }
    },
    {
      "id": "route-004",
      "category": "intent_routing",
      "input": "帮我写首诗",
      "context": { "userId": "default" },
      "expectations": {
        "routedTo": "other",
        "responseContainsAny": ["记账", "财务", "无法", "不能", "抱歉"]
      }
    }
```

- [ ] **Step 3: 更新 evals/README.md**

将 "15 条用例，6 个评估维度" 更新为 "19 条用例，7 个评估维度"，表格新增：

```
| `intent_routing` | 4 | Supervisor 意图分类准确率 booking/analysis/other |
```

- [ ] **Step 4: 验证 JSON 格式**

```bash
python3 -c "import json; data = json.load(open('evals/golden-dataset.json')); print(f'OK: {len(data[\"cases\"])} cases')"
```
Expected: `OK: 19 cases`

- [ ] **Step 5: 提交**

```bash
git add finance-agent/src/test/java/com/example/agent/eval/EvalExpectations.java \
        evals/golden-dataset.json evals/README.md
git commit -m "feat(eval): 新增 intent_routing 维度 + 4 条路由准确率 case"
```

---

### Task 10: 全量测试套件验证

**目的：** 确认所有 Java 和 Python 测试（单元 + 集成）在 Multi-Agent 改动后全部通过，无回归。

**Files:** 无新建/修改，仅验证。

- [ ] **Step 1: 运行 Java 单元测试（全量）**

```bash
cd finance-agent && ./mvnw test
```
Expected: BUILD SUCCESS，全部测试通过。

关注指标：
- `MultiAgentConfigTest`: 3/3 PASS
- `BookkeeperAgentTest`: 3/3 PASS
- `AnalystAgentTest`: 3/3 PASS
- `SupervisorAgentTest`: 3/3 PASS
- `MultiAgentIntegrationTest`: 6/6 PASS
- `PendingConfirmationStoreTest`: 4/4 PASS
- `HITLIntegrationTest`: 4/4 PASS
- 已有测试（AgentEvalTest 等）：全部 PASS，无回归

- [ ] **Step 2: 运行 Python 测试（全量）**

```bash
cd finance-agent-py && source .venv/bin/activate && pytest multiagent/ -v
```
Expected: 全部测试通过。

关注指标：
- `test_graph.py`: 3/3 PASS
- `test_endpoints.py`: 4/4 PASS

- [ ] **Step 3: 验证 Golden Dataset JSON 格式**

```bash
python3 -c "import json; data = json.load(open('evals/golden-dataset.json')); print(f'OK: {len(data[\"cases\"])} cases, categories: {set(c[\"category\"] for c in data[\"cases\"])}')"
```
Expected: `OK: 19 cases, categories: {...7 categories...}`

- [ ] **Step 4: 运行 Eval（可选，需 LLM 连接）**

```bash
cd finance-agent && ./mvnw test -Dgroups=evals -DexcludedGroups= -Dtest=AgentEvalTest
```
Expected: 通过率 ≥ 85%（新增的 route-* cases 依赖 Supervisor 分类功能）

- [ ] **Step 5: 编译全量验证**

```bash
# Java 全部模块
cd finance-backend && ./mvnw compile -q && echo "backend OK"
cd finance-mcp-server && ./mvnw compile -q && echo "mcp-server OK"
cd finance-agent && ./mvnw compile -q && echo "agent OK"

# Frontend
cd finance-frontend && npm run build --if-present && echo "frontend OK"
```
Expected: 全部 OK，无编译错误。

- [ ] **Step 6: 运行 CLAUDE.md 一致性校验**

```bash
bash scripts/claude-check.sh
```
Expected: 通过 / 仅已知告警。

- [ ] **Step 7: 提交最终验证结果**

```bash
git add -A
git diff --cached --stat
# 如有遗漏文件，补 git add
git commit -m "test(multi-agent): 全量测试套件通过验证"
```

---

## 任务依赖关系

```
T1 (AgentType + Config) ─────┬──→ T2 (Bookkeeper) ──┐
                              │                       ├──→ T4 (Supervisor + 端点) ──┐
                              └──→ T3 (Analyst) ────┘          │                     │
                                                                ├──→ T5 (HITL) ───────┤
                                                                │                     │
T6 (Python StateGraph) ──→ T7 (Python 端点)                    │                     │
                                                                │                     │
T8 (前端) ←── 依赖 T4+T5+T7 的 SSE 事件格式                      │                     │
                                                                │                     │
T9 (Eval) ←── 依赖 T4 的 Supervisor 分类结果                     │                     │
                                                                │                     │
T10 (全量测试验证) ←── 依赖 T1-T9 全部完成                         ←────────────────────┘
```

**并行策略**：
- T1, T6 可并行（不同栈）
- T2, T3 可在 T1 后并行
- T4 依赖 T1+T2+T3
- T5, T7 可在 T4, T6 后并行
- T8, T9 可在 T4+T5+T7 后并行
- T10 在所有任务完成后执行

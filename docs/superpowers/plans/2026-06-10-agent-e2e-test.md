# Agent 端到端测试体系 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 Multi-Agent 端到端测试覆盖，统一 Single/Multi 两种模式的测试风格，防止 conversationId 类管道 bug 再次漏过。

**Architecture:** 抽取 `ChatEndpointTestBase` 共享基类（.env 加载、SSE 解析、流式请求辅助），将现有 `ChatControllerTest` 重命名为 `SingleAgentEndpointTest`，新增 `MultiAgentEndpointTest` 覆盖 7 个端到端场景。所有测试使用真实 LLM 调用，通过 `@ExtendWith(LlmCondition)` 在无 LLM 环境自动跳过。

**Tech Stack:** JUnit 5, Spring Boot Test, MockMvc (async), AiResponseValidator

**Spec:** `docs/superpowers/specs/2026-06-10-agent-e2e-test-design.md`

---

### Task 1: 创建 ChatEndpointTestBase 共享基类

**Files:**
- Create: `finance-agent/src/test/java/com/example/agent/controller/ChatEndpointTestBase.java`

- [ ] **Step 1: 创建基类文件**

```java
package com.example.agent.controller;

import com.example.agent.config.LlmCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.FileInputStream;
import java.util.Properties;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(LlmCondition.class)
abstract class ChatEndpointTestBase {

    static {
        loadDotEnv();
    }

    private static void loadDotEnv() {
        String[] paths = {"../.env", ".env"};
        for (String path : paths) {
            try (FileInputStream in = new FileInputStream(path)) {
                Properties props = new Properties();
                props.load(in);
                props.forEach((key, value) -> {
                    String envKey = key.toString();
                    String envValue = value.toString().trim();
                    if (!envValue.isEmpty()) {
                        System.setProperty(envKey, envValue);
                    }
                });
                return;
            } catch (Exception ignored) {
            }
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected String chatAndGetReply(String userId, String message) throws Exception {
        String json = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractReply(json);
    }

    protected String extractReply(String json) throws Exception {
        return objectMapper.readTree(json).get("reply").asText();
    }

    protected String extractSseContent(String sseText) {
        StringBuilder sb = new StringBuilder();
        for (String line : sseText.split("\n")) {
            if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                sb.append(data);
            }
        }
        return sb.toString();
    }

    protected String streamAndGetContent(String endpoint, String userId, String message) throws Exception {
        String raw = streamAndGetRaw(endpoint, userId, message);
        return extractSseContent(raw);
    }

    protected String streamAndGetRaw(String endpoint, String userId, String message) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post(endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        return mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw compile -pl . -q -Dmaven.test-skip=false -f pom.xml test-compile 2>&1 | tail -5`

Expected: 编译通过，无错误。

- [ ] **Step 3: 提交**

```bash
git add finance-agent/src/test/java/com/example/agent/controller/ChatEndpointTestBase.java
git commit -m "test: 新增 ChatEndpointTestBase 共享基类

抽取 .env 加载、SSE 解析、流式请求等公共方法，
供 Single/Multi Agent 端到端测试共用。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 2: 重命名 ChatControllerTest → SingleAgentEndpointTest

**Files:**
- Delete: `finance-agent/src/test/java/com/example/agent/controller/ChatControllerTest.java`
- Create: `finance-agent/src/test/java/com/example/agent/controller/SingleAgentEndpointTest.java`

- [ ] **Step 1: 创建 SingleAgentEndpointTest**

从现有 `ChatControllerTest` 重构：继承基类，删除重复方法。

```java
package com.example.agent.controller;

import com.example.agent.validation.AiResponseValidator;
import com.example.agent.validation.AiResponseValidator.ValidationCriteria;
import com.example.agent.validation.AiResponseValidator.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

class SingleAgentEndpointTest extends ChatEndpointTestBase {

    @Test
    void shouldReturnNonEmptyResponse() throws Exception {
        String json = mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"default\",\"message\":\"我的账户余额是多少？\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).contains("reply");

        String reply = extractReply(json);
        assertThat(AiResponseValidator.isNotEmpty(reply)).isTrue();
    }

    @Test
    void shouldNotContainDegradationText() throws Exception {
        String reply = chatAndGetReply("default", "帮我查询一下交易记录");
        assertThat(AiResponseValidator.hasDegradation(reply))
                .as("响应不应包含降级文案: " + reply)
                .isFalse();
    }

    @Test
    void shouldMentionFinancialDataWhenQueryingBalance() throws Exception {
        String reply = chatAndGetReply("default", "我的账户余额是多少？");
        ValidationResult result = AiResponseValidator.validate(reply,
                new ValidationCriteria(List.of("元", "余额"), null, 20, 2000));
        assertThat(result.passed())
                .as("查询余额响应验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldHandleDiningExpenseQuery() throws Exception {
        String reply = chatAndGetReply("default", "帮我看下我在餐饮上花了多少钱");
        ValidationResult result = AiResponseValidator.validate(reply,
                new ValidationCriteria(List.of("餐饮", "元"), null, 30, 3000));
        assertThat(result.passed())
                .as("餐饮查询响应验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldStreamWithSSEHeaders() throws Exception {
        streamAndGetRaw("/api/chat/stream", "default", "你好");
    }

    @Test
    void shouldStreamTokensWithDataPrefix() throws Exception {
        String raw = streamAndGetRaw("/api/chat/stream", "default", "你好");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }

    @Test
    void shouldStreamCompleteResponse() throws Exception {
        String content = streamAndGetContent("/api/chat/stream", "default", "说一句话就好");
        assertThat(content).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(content)).isFalse();
    }
}
```

- [ ] **Step 2: 删除旧文件**

```bash
git rm finance-agent/src/test/java/com/example/agent/controller/ChatControllerTest.java
```

- [ ] **Step 3: 编译验证**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test-compile -q 2>&1 | tail -5`

Expected: 编译通过。

- [ ] **Step 4: 运行 SingleAgentEndpointTest 验证行为不变**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -Dtest="SingleAgentEndpointTest" -q 2>&1 | tail -10`

Expected: 7 个测试全部通过（或无 LLM 环境时全部跳过）。

- [ ] **Step 5: 提交**

```bash
git add finance-agent/src/test/java/com/example/agent/controller/SingleAgentEndpointTest.java
git commit -m "refactor: 重命名 ChatControllerTest → SingleAgentEndpointTest

继承 ChatEndpointTestBase，删除重复的私有方法。
7 个用例逻辑不变，仅改继承关系。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 3: 创建 MultiAgentEndpointTest — 管道健壮性用例

**Files:**
- Create: `finance-agent/src/test/java/com/example/agent/controller/MultiAgentEndpointTest.java`

先写 3 个管道健壮性用例（验证不炸），再在 Task 4 补充行为正确性用例。

- [ ] **Step 1: 写 3 个管道健壮性测试（预期全部通过）**

```java
package com.example.agent.controller;

import com.example.agent.validation.AiResponseValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentEndpointTest extends ChatEndpointTestBase {

    private static final String MULTI_AGENT_STREAM = "/api/chat/multi-agent/stream";

    @Test
    void shouldStreamFromMultiAgentEndpoint() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "default", "你好");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }

    @Test
    void shouldHandleNonFinancialInput() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "今天天气怎么样");
        assertThat(AiResponseValidator.isNotEmpty(content)).isTrue();
    }

    @Test
    void shouldWorkWithDifferentUsers() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "e2e-test-user", "我有几个账户");
        assertThat(raw).isNotEmpty();
        assertThat(raw).contains("data:");
    }
}
```

- [ ] **Step 2: 运行测试验证管道健壮性**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -Dtest="MultiAgentEndpointTest" -q 2>&1 | tail -10`

Expected: 3 个测试全部通过。如果 `shouldWorkWithDifferentUsers` 失败（说明 conversationId 修复没生效），先确认 `ChatController:405` 已改为 `"multi-" + userId`。

- [ ] **Step 3: 提交**

```bash
git add finance-agent/src/test/java/com/example/agent/controller/MultiAgentEndpointTest.java
git commit -m "test: 新增 MultiAgentEndpointTest — 管道健壮性用例

覆盖 Multi-Agent SSE 流式端点的基本可用性：
- 流正常返回
- 非财务输入不崩溃
- 不同 userId 的 conversationId 校验通过

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 4: 补充 MultiAgentEndpointTest — 行为正确性用例

**Files:**
- Modify: `finance-agent/src/test/java/com/example/agent/controller/MultiAgentEndpointTest.java`

- [ ] **Step 1: 追加 4 个行为正确性测试**

在 `MultiAgentEndpointTest` 类中追加以下方法：

```java
    @Test
    void shouldRouteAnalysisToAnalyst() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "看下我在餐饮上花了多少钱");
        AiResponseValidator.ValidationResult result = AiResponseValidator.validate(content,
                new AiResponseValidator.ValidationCriteria(
                        java.util.List.of("餐饮"), null, 30, 3000));
        assertThat(result.passed())
                .as("分析师路由验证失败: " + result.failures())
                .isTrue();
    }

    @Test
    void shouldRouteBookkeepingToBookkeeper() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "我有哪些账户");
        assertThat(content).isNotEmpty();
        assertThat(AiResponseValidator.hasDegradation(content)).isFalse();
        boolean hasAccountInfo = content.contains("账户") || content.contains("储蓄") || content.contains("支付宝")
                || content.contains("微信") || content.contains("现金");
        assertThat(hasAccountInfo)
                .as("记账员路由验证失败，响应中未包含账户信息: " + content)
                .isTrue();
    }

    @Test
    void shouldReturnAgentIdentityEvent() throws Exception {
        String raw = streamAndGetRaw(MULTI_AGENT_STREAM, "default", "查一下最近的支出");
        assertThat(raw).contains("event:thinking");
        boolean hasAgentIdentity = raw.contains("分析师") || raw.contains("记账员");
        assertThat(hasAgentIdentity)
                .as("SSE 流中未包含 Agent 身份标识: " + raw.substring(0, Math.min(200, raw.length())))
                .isTrue();
    }

    @Test
    void shouldReturnCompleteAnalysisResponse() throws Exception {
        String content = streamAndGetContent(MULTI_AGENT_STREAM, "default", "帮我汇总一下上个月的收支");
        assertThat(content.length()).isGreaterThan(30);
        boolean hasFinancialContent = content.contains("收入") || content.contains("支出")
                || content.contains("元") || content.contains("¥");
        assertThat(hasFinancialContent)
                .as("汇总响应缺少财务内容: " + content)
                .isTrue();
    }
```

- [ ] **Step 2: 运行全部 MultiAgentEndpointTest**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -Dtest="MultiAgentEndpointTest" -q 2>&1 | tail -10`

Expected: 7 个测试全部通过。

- [ ] **Step 3: 提交**

```bash
git add finance-agent/src/test/java/com/example/agent/controller/MultiAgentEndpointTest.java
git commit -m "test: 补充 MultiAgentEndpointTest — 行为正确性用例

覆盖 Supervisor 分派 + Specialist 执行的端到端行为：
- Analyst 路由（餐饮查询）
- Bookkeeper 路由（账户查询）
- Agent 身份标识事件
- 完整汇总响应

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

### Task 5: 全量回归验证

**Files:** 无变更，纯验证。

- [ ] **Step 1: 运行 Single + Multi 全部端到端测试**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -Dtest="SingleAgentEndpointTest,MultiAgentEndpointTest" 2>&1 | tail -15`

Expected: 14 个测试全部通过（7 Single + 7 Multi）。

- [ ] **Step 2: 运行 finance-agent 全部测试确认无回归**

Run: `cd finance-agent && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -q 2>&1 | tail -10`

Expected: 全部通过，无新增失败。

- [ ] **Step 3: 运行 finance-backend 全部测试确认无回归**

Run: `cd finance-backend && export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home && ./mvnw test -q 2>&1 | tail -10`

Expected: 53 个测试全部通过（含之前修复的 5 个）。

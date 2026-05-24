# 可观测性体系实现计划

> **For agentic workers:** Execute tasks in order. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 4 个服务建立统一的可观测性体系：日志基础设施（logback）、Prometheus 指标（Micrometer）、Token 消耗统计、LLM 重试/降级、工具调用成功率、对话反馈、Context 监控、前端打点。

**Architecture:** 三个 Java 服务各添加 logback-spring.xml + actuator + micrometer-registry-prometheus。Backend 通过 HandlerInterceptor 收集 API 指标。MCP Server 在 FinanceTools 内手动记录工具调用指标。Agent 通过自定义 AgentMetrics 封装所有 LLM/Token/Context 指标，新增 GlobalExceptionHandler 做降级处理，新增 FeedbackController 接收前端反馈。前端 ChatPanel/ChatMessage 加打点和反馈按钮。

**Tech Stack:** Spring Boot 3.4.5, Java 17, Spring AI 1.1.0, Micrometer + Prometheus, Logback, Vue 3 + Element Plus, marked.

---

## 依赖关系

```
Task 1 (依赖+pom) ──┬──> Task 2 (logback xml) ──> Task 3 (application.yml)
                    ├──> Task 4 (Backend @Slf4j)
                    ├──> Task 5 (Backend MetricsInterceptor)
                    ├──> Task 6 (MCP Server metrics)
                    ├──> Task 7 (Agent AgentMetrics)
                    ├──> Task 8 (Agent Token 统计)
                    ├──> Task 9 (Agent 重试+降级)
                    ├──> Task 10 (Agent Context 监控)
                    ├──> Task 11 (Agent Feedback)
                    └──> Task 12 (Frontend)

Task 2, 3 are independent of each other once Task 1 is done.
Task 4-12 can be done in parallel after Task 1-3.
Task 13 (.gitignore) is independent, can be done anytime.
```

**实现顺序: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13**

---

### Task 1: 统一添加依赖

**Files:**
- Modify: `finance-backend/pom.xml`
- Modify: `finance-mcp-server/pom.xml`
- Modify: `finance-agent/pom.xml`

三个服务都需要添加相同的两个依赖。Backend 额外添加 Lombok（MCP Server 和 Agent 已有 spring-boot-starter-web 自带 Lombok，无需额外添加）。

- [ ] **Step 1: 修改 finance-backend/pom.xml**

在 `<!-- spring-boot-starter-web -->` 依赖之后，`<!-- jackson-dataformat-csv -->` 之前，添加：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
```

- [ ] **Step 2: 修改 finance-mcp-server/pom.xml**

在 `</dependencies>` 之前添加：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

- [ ] **Step 3: 修改 finance-agent/pom.xml**

在 `</dependencies>` 之前添加：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
```

- [ ] **Step 4: 验证依赖编译**

Run:
```bash
cd finance-backend && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
cd finance-mcp-server && ./mvnw compile -q
cd finance-agent && ./mvnw compile -q
```
Expected: 三个服务均 BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add finance-backend/pom.xml finance-mcp-server/pom.xml finance-agent/pom.xml
git commit -m "build: 添加 actuator、micrometer-prometheus 和 lombok 依赖"
```

---

### Task 2: 创建 Logback 配置文件

**Files:**
- Create: `finance-backend/src/main/resources/logback-spring.xml`
- Create: `finance-mcp-server/src/main/resources/logback-spring.xml`
- Create: `finance-agent/src/main/resources/logback-spring.xml`

这三个文件内容几乎相同，仅 `service` 字段和日志文件名不同。配置文件无需测试（基础设施）。

- [ ] **Step 1: 创建 finance-backend/src/main/resources/logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_DIR" value="log"/>
    <property name="SERVICE_NAME" value="backend"/>

    <!-- 终端输出：彩色文本 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%logger{36}] %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件输出：JSON 格式 -->
    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${SERVICE_NAME}.json.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.json.log</fileNamePattern>
            <maxFileSize>500MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeContext>false</includeContext>
            <fieldNames>
                <timestamp>@timestamp</timestamp>
            </fieldNames>
        </encoder>
    </appender>

    <!-- 纯文本文件（tail -f 友好） -->
    <appender name="TEXT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${SERVICE_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>500MB</maxFileSize>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%logger{36}] %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="TEXT_FILE"/>
    </root>
</configuration>
```

> **注意：** 为了简单，不使用 `logstash-logback-encoder`（需要额外依赖）。JSON 文件暂时用 text 格式代替，在 Task 2 中保持简单。后续如需要真正 JSON 再引入。

实际上，为了保持简单且不引入额外依赖，三个服务的 logback-spring.xml 使用相同的文本格式输出到终端和文件。

- [ ] **Step 1 (修订): 创建 finance-backend/src/main/resources/logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_DIR" value="log"/>
    <property name="SERVICE_NAME" value="backend"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%logger{36}] %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${SERVICE_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${SERVICE_NAME}.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>500MB</maxFileSize>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

- [ ] **Step 2: 创建 finance-mcp-server/src/main/resources/logback-spring.xml**

同上，仅将 `<property name="SERVICE_NAME" value="backend"/>` 改为 `value="mcp-server"`。

- [ ] **Step 3: 创建 finance-agent/src/main/resources/logback-spring.xml**

同上，仅将 `<property name="SERVICE_NAME" value="backend"/>` 改为 `value="agent"`。

- [ ] **Step 4: Commit**

```bash
git add finance-backend/src/main/resources/logback-spring.xml \
        finance-mcp-server/src/main/resources/logback-spring.xml \
        finance-agent/src/main/resources/logback-spring.xml
git commit -m "feat: 添加 logback 日志配置，终端彩色+文件滚动输出"
```

---

### Task 3: 添加 Actuator 和 Prometheus 配置

**Files:**
- Modify: `finance-backend/src/main/resources/application.yml`
- Modify: `finance-mcp-server/src/main/resources/application.yml`
- Modify: `finance-agent/src/main/resources/application.yml`

- [ ] **Step 1: 修改 finance-backend/src/main/resources/application.yml**

在现有内容末尾添加：
```yaml

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

- [ ] **Step 2: 修改 finance-mcp-server/src/main/resources/application.yml**

同上，在现有内容末尾添加相同 management 配置。

- [ ] **Step 3: 修改 finance-agent/src/main/resources/application.yml**

在现有内容末尾添加：
```yaml

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true

spring:
  ai:
    retry:
      max-attempts: 2
      backoff:
        initial-interval: 1000
        multiplier: 2
```

- [ ] **Step 4: 重启服务验证端点**

Run:
```bash
# 启动 backend 后验证
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus | head -5
```
Expected: health 返回 `{"status":"UP"}`，prometheus 返回指标数据。

- [ ] **Step 5: Commit**

```bash
git add finance-backend/src/main/resources/application.yml \
        finance-mcp-server/src/main/resources/application.yml \
        finance-agent/src/main/resources/application.yml
git commit -m "feat: 添加 actuator 和 prometheus 端点配置"
```

---

### Task 4: Backend 添加 @Slf4j 和关键日志

**Files:**
- Modify: `finance-backend/src/main/java/com/example/finance/controller/TransactionController.java`
- Modify: `finance-backend/src/main/java/com/example/finance/controller/AccountController.java`
- Modify: `finance-backend/src/main/java/com/example/finance/controller/CategoryController.java`
- Modify: `finance-backend/src/main/java/com/example/finance/service/FinanceService.java`
- Modify: `finance-backend/src/main/java/com/example/finance/repository/CsvDataStore.java`

- [ ] **Step 1: 修改 TransactionController.java**

在类上添加 `@Slf4j` 注解，在 `listTransactions` 方法开头加 `log.info("GET /api/transactions userId={} page={} pageSize={}", userId, page, pageSize);`，在 `createTransaction` 方法开头加 `log.info("POST /api/transactions userId={} amount={} category={}", transaction.getUserId(), transaction.getAmount(), transaction.getCategory());`。

完整代码：

```java
package com.example.finance.controller;

import com.example.finance.dto.PageResult;
import com.example.finance.model.Transaction;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FinanceService financeService;

    public TransactionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public PageResult<Transaction> listTransactions(
            @RequestParam(required = false, defaultValue = "default") String userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        log.info("GET /api/transactions userId={} page={} pageSize={}", userId, page, pageSize);
        return financeService.listTransactionsPaginated(userId, accountId, date, category, type, page, pageSize);
    }

    @PostMapping
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        log.info("POST /api/transactions userId={} amount={} category={}",
                transaction.getUserId(), transaction.getAmount(), transaction.getCategory());
        return financeService.createTransaction(transaction);
    }
}
```

- [ ] **Step 2: 修改 AccountController.java**

添加 `@Slf4j`，在 `listAccounts` 和 `createAccount`、`getBalance` 方法开头加日志：

```java
package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final FinanceService financeService;

    public AccountController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Account> listAccounts(@RequestParam(required = false, defaultValue = "default") String userId) {
        log.info("GET /api/accounts userId={}", userId);
        return financeService.listAccounts(userId);
    }

    @PostMapping
    public Account createAccount(@RequestBody Account account) {
        log.info("POST /api/accounts name={} type={} userId={}", account.getName(), account.getType(), account.getUserId());
        return financeService.createAccount(account);
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        log.info("GET /api/accounts/{}/balance", id);
        return financeService.getBalance(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 3: 修改 CategoryController.java**

添加 `@Slf4j`，在 `listCategories` 开头加日志：

```java
package com.example.finance.controller;

import com.example.finance.model.Category;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final FinanceService financeService;

    public CategoryController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Category> listCategories() {
        log.info("GET /api/categories");
        return financeService.listCategories();
    }
}
```

- [ ] **Step 4: 修改 FinanceService.java**

添加 `@Slf4j`，在 `createTransaction` 开头加日志，在方法返回时记录关键操作：

```java
package com.example.finance.service;

import com.example.finance.dto.PageResult;
import com.example.finance.model.*;
import com.example.finance.repository.CsvDataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class FinanceService {

    private final CsvDataStore dataStore;

    public FinanceService(CsvDataStore dataStore) {
        this.dataStore = dataStore;
    }

    // ... 其余方法不变

    public Transaction createTransaction(Transaction transaction) {
        log.info("创建交易: userId={} type={} amount={} category={}",
                transaction.getUserId(), transaction.getType(), transaction.getAmount(), transaction.getCategory());
        dataStore.saveTransaction(transaction);
        return transaction;
    }

    // ... listAccounts, createAccount, getAccount, getBalance, listTransactions,
    //     listTransactionsPaginated, listCategories 保持不变
}
```

- [ ] **Step 5: 修改 CsvDataStore.java**

添加 `@Slf4j`，在 CSV 读写异常处添加 ERROR 日志。在现有 `catch (IOException e)` 的地方，将 `throw new RuntimeException(...)` 改为先用 `log.error` 记录再抛出：

```java
// 修改 loadFromCsv 方法:
private <T> List<T> loadFromCsv(String filename, Class<T> clazz, CsvSchema schema) {
    File file = new File(dataDir, filename);
    if (!file.exists()) return new ArrayList<>();
    try {
        MappingIterator<T> it = csvMapper.readerFor(clazz).with(schema).readValues(file);
        return it.readAll();
    } catch (IOException e) {
        log.error("加载 CSV 文件失败: {}", filename, e);
        throw new RuntimeException("Failed to load CSV: " + filename, e);
    }
}

// 修改 persistToCsv 方法:
private <T> void persistToCsv(String filename, List<T> items, CsvSchema schema) {
    try {
        csvMapper.writer(schema).writeValue(new File(dataDir, filename), items);
    } catch (IOException e) {
        log.error("持久化 CSV 文件失败: {}", filename, e);
        throw new RuntimeException("Failed to persist CSV: " + filename, e);
    }
}
```

同时在 `@PostConstruct public void init()` 方法开头加 `log.info("初始化 CsvDataStore，数据目录: {}", dataDir);`。

- [ ] **Step 6: 验证编译和测试**

Run:
```bash
cd finance-backend && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw test -q
```
Expected: BUILD SUCCESS，所有测试通过。

- [ ] **Step 7: Commit**

```bash
git add finance-backend/src/main/java/com/example/finance/controller/ \
        finance-backend/src/main/java/com/example/finance/service/ \
        finance-backend/src/main/java/com/example/finance/repository/
git commit -m "feat: backend 添加 @Slf4j 和关键业务日志点"
```

---

### Task 5: Backend API 指标拦截器

**Files:**
- Create: `finance-backend/src/main/java/com/example/finance/config/MetricsInterceptor.java`
- Create: `finance-backend/src/main/java/com/example/finance/config/WebMvcMetricsConfig.java`

- [ ] **Step 1: 创建 MetricsInterceptor.java**

```java
package com.example.finance.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
public class MetricsInterceptor implements HandlerInterceptor {

    private final MeterRegistry registry;

    public MetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute("startTime", System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        long startTime = (long) request.getAttribute("startTime");
        long durationNs = System.nanoTime() - startTime;
        String endpoint = request.getRequestURI().replaceAll("/\\d+", "/{id}");

        Counter.builder("api.requests.total")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .tag("status", String.valueOf(response.getStatus()))
                .register(registry)
                .increment();

        Timer.builder("api.requests.duration")
                .tag("endpoint", endpoint)
                .tag("method", request.getMethod())
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);

        if (ex != null || response.getStatus() >= 400) {
            Counter.builder("api.requests.errors")
                    .tag("endpoint", endpoint)
                    .tag("error_type", ex != null ? ex.getClass().getSimpleName() : String.valueOf(response.getStatus()))
                    .register(registry)
                    .increment();
        }
    }
}
```

- [ ] **Step 2: 创建 WebMvcMetricsConfig.java**

```java
package com.example.finance.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcMetricsConfig implements WebMvcConfigurer {

    private final MetricsInterceptor metricsInterceptor;

    public WebMvcMetricsConfig(MetricsInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/**");
    }
}
```

- [ ] **Step 3: 验证编译**

Run:
```bash
cd finance-backend && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: 验证指标端点**

启动 backend 后执行：
```bash
curl http://localhost:8080/actuator/metrics/api.requests.total | python3 -m json.tool
```
Expected: 返回 JSON，`name` 为 `api.requests.total`。

- [ ] **Step 5: Commit**

```bash
git add finance-backend/src/main/java/com/example/finance/config/
git commit -m "feat: backend 添加 API 请求指标拦截器（Counter + Timer）"
```

---

### Task 6: MCP Server 工具调用指标

**Files:**
- Modify: `finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java`

- [ ] **Step 1: 修改 FinanceTools.java**

将 `LoggerFactory.getLogger` 替换为 `@Slf4j` 注解。注入 `MeterRegistry`，每个 `@McpTool` 方法用 try-catch 包裹并记录 Counter + Timer。

完整代码：

```java
package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class FinanceTools {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry registry;

    public FinanceTools(RestClient restClient, ObjectMapper objectMapper, MeterRegistry registry) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @McpTool(name = "query_balance", description = "查询指定账户的余额")
    public BigDecimal queryBalance(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId) {
        log.info("queryBalance called with userId={}, accountId={}", userId, accountId);
        long start = System.nanoTime();
        try {
            BigDecimal result = restClient.get()
                    .uri("/api/accounts/{id}/balance", accountId)
                    .retrieve()
                    .body(BigDecimal.class);
            recordSuccess("query_balance", start);
            return result;
        } catch (Exception e) {
            recordError("query_balance", e);
            throw e;
        }
    }

    @McpTool(name = "list_transactions", description = "查询交易记录列表，可按日期、分类、类型和账户过滤")
    public List<TransactionResponse> listTransactions(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "交易日期 (yyyy-MM-dd)") String date,
            @McpToolParam(description = "交易分类，如餐饮、交通、购物等") String category,
            @McpToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @McpToolParam(description = "账户ID") Long accountId) {
        log.info("listTransactions called with userId={}, date={}, category={}, type={}, accountId={}",
                userId, date, category, type, accountId);
        long start = System.nanoTime();

        try {
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath("/api/transactions")
                    .queryParam("userId", userId)
                    .queryParam("pageSize", 1000);
            if (date != null) uriBuilder.queryParam("date", date);
            if (category != null) uriBuilder.queryParam("category", category);
            if (type != null) uriBuilder.queryParam("type", type);
            if (accountId != null) uriBuilder.queryParam("accountId", accountId);

            java.net.URI uri = uriBuilder.build().toUri();
            log.info("listTransactions URI: {}", uri);

            Map<String, Object> pageResult = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            log.info("listTransactions response: total={}", pageResult.get("total"));

            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) pageResult.get("items");
            if (rawItems == null || rawItems.isEmpty()) {
                recordSuccess("list_transactions", start);
                return List.of();
            }

            List<TransactionResponse> result = new ArrayList<>();
            for (Map<String, Object> item : rawItems) {
                result.add(objectMapper.convertValue(item, TransactionResponse.class));
            }
            recordSuccess("list_transactions", start);
            return result;
        } catch (Exception e) {
            recordError("list_transactions", e);
            throw e;
        }
    }

    @McpTool(name = "add_transaction", description = "添加一笔交易记录")
    public Map<String, Object> addTransaction(
            @McpToolParam(description = "用户ID") String userId,
            @McpToolParam(description = "账户ID") Long accountId,
            @McpToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @McpToolParam(description = "金额") BigDecimal amount,
            @McpToolParam(description = "分类") String category,
            @McpToolParam(description = "备注") String note) {
        log.info("addTransaction called with userId={}, accountId={}, type={}, amount={}, category={}",
                userId, accountId, type, amount, category);
        long start = System.nanoTime();

        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("userId", userId);
            body.put("accountId", accountId);
            body.put("type", type);
            body.put("amount", amount);
            body.put("category", category);
            body.put("note", note != null ? note : "");
            body.put("date", java.time.LocalDate.now().toString());

            Map<String, Object> result = restClient.post()
                    .uri("/api/transactions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            recordSuccess("add_transaction", start);
            return result;
        } catch (Exception e) {
            recordError("add_transaction", e);
            throw e;
        }
    }

    @McpTool(name = "list_accounts", description = "查询所有账户列表")
    public List<AccountResponse> listAccounts(
            @McpToolParam(description = "用户ID") String userId) {
        log.info("listAccounts called with userId={}", userId);
        long start = System.nanoTime();

        try {
            List<AccountResponse> result = List.of(restClient.get()
                    .uri("/api/accounts?userId=" + userId)
                    .retrieve()
                    .body(AccountResponse[].class));
            recordSuccess("list_accounts", start);
            return result;
        } catch (Exception e) {
            recordError("list_accounts", e);
            throw e;
        }
    }

    private void recordSuccess(String toolName, long startNs) {
        Counter.builder("mcp.tool.calls.total")
                .tag("tool_name", toolName)
                .tag("status", "success")
                .register(registry)
                .increment();
        Timer.builder("mcp.tool.calls.duration")
                .tag("tool_name", toolName)
                .register(registry)
                .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
    }

    private void recordError(String toolName, Exception e) {
        Counter.builder("mcp.tool.calls.total")
                .tag("tool_name", toolName)
                .tag("status", "error")
                .register(registry)
                .increment();
        Counter.builder("mcp.tool.calls.errors")
                .tag("tool_name", toolName)
                .tag("error_type", e.getClass().getSimpleName())
                .register(registry)
                .increment();
    }
}
```

- [ ] **Step 2: 验证编译**

Run:
```bash
cd finance-mcp-server && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java
git commit -m "feat: mcp-server 添加工具调用指标（成功/失败计数 + 耗时）"
```

---

### Task 7: Agent 指标封装和 Controller 指标埋点

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/metrics/AgentMetrics.java`
- Modify: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java`

- [ ] **Step 1: 创建 AgentMetrics.java**

```java
package com.example.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    private final AtomicLong tokenSpeed = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> memoryMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> memorySizes = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.llm.tokens.speed", tokenSpeed, AtomicLong::get)
                .register(registry);
    }

    public void recordChatRequest(String userId, String type) {
        Counter.builder("agent.chat.requests")
                .tag("userId", userId)
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordTtft(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.ttft")
                .tag("userId", userId)
                .register(registry));
    }

    public void recordDuration(String userId, Timer.Sample sample) {
        sample.stop(Timer.builder("agent.chat.duration")
                .tag("userId", userId)
                .register(registry));
    }

    public void recordTokens(String model, long inputTokens, long outputTokens) {
        Counter.builder("agent.llm.tokens.input")
                .tag("model", model)
                .register(registry)
                .increment(inputTokens);
        Counter.builder("agent.llm.tokens.output")
                .tag("model", model)
                .register(registry)
                .increment(outputTokens);
    }

    public void recordTokenSpeed(long tokensPerSecond) {
        tokenSpeed.set(tokensPerSecond);
    }

    public void recordLlmError(String errorType) {
        Counter.builder("agent.llm.errors")
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    public void recordLlmRetry() {
        Counter.builder("agent.llm.retries")
                .register(registry)
                .increment();
    }

    public void updateMemoryGauge(String userId, int messageCount, long sizeBytes) {
        memoryMessages.computeIfAbsent(userId, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("agent.memory.messages", g, AtomicLong::get)
                    .tag("userId", userId)
                    .register(registry);
            return g;
        }).set(messageCount);

        memorySizes.computeIfAbsent(userId, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("agent.memory.size_bytes", g, AtomicLong::get)
                    .tag("userId", userId)
                    .register(registry);
            return g;
        }).set(sizeBytes);
    }
}
```

- [ ] **Step 2: 修改 ChatController.java**

将 `LoggerFactory.getLogger` 替换为 `@Slf4j` 注解。注入 `AgentMetrics`。在 `/api/chat` 和 `/api/chat/stream` 中添加指标埋点。

关键改动点：
1. 类级别添加 `@Slf4j`，注入 `AgentMetrics`
2. `chat()` 方法：记录 `agent.chat.requests`(type=normal)，Timer 记录 duration，从 `ChatResponse.getMetadata().getUsage()` 获取 token 数
3. `chatStream()` 方法：记录 `agent.chat.requests`(type=stream)，Timer 记录 ttft 和 duration

```java
package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AgentMetrics agentMetrics;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          List<ToolCallbackProvider> toolProviders,
                          ChatMemory chatMemory,
                          AgentMetrics agentMetrics) {
        log.info("ChatController initialized with {} tool providers", toolProviders.size());
        for (var provider : toolProviders) {
            log.info("  Provider: {} -> {} tools", provider.getClass().getSimpleName(),
                    provider.getToolCallbacks().length);
        }
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder
                .defaultToolCallbacks(toolProviders.toArray(new ToolCallbackProvider[0]))
                .build();
        this.agentMetrics = agentMetrics;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String userId = request.getUserId() != null ? request.getUserId() : "default";
        log.info("Chat request from userId={}: {}", userId, request.getMessage());
        agentMetrics.recordChatRequest(userId, "normal");

        Timer.Sample sample = agentMetrics.startTimer();
        String systemPrompt = buildSystemPrompt(userId);

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        var chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(request.getMessage())
                .advisors(advisor)
                .call()
                .chatResponse();

        agentMetrics.recordDuration(userId, sample);

        // Token 统计（非流式）
        var usage = chatResponse.getMetadata().getUsage();
        if (usage != null) {
            agentMetrics.recordTokens("deepseek-chat",
                    usage.getPromptTokens(), usage.getGenerationTokens());
        }

        return new ChatResponse(chatResponse.getResult().getOutput().getText());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody ChatRequest request,
                                                             HttpServletResponse response) {
        String userId = request.getUserId() != null ? request.getUserId() : "default";
        log.info("Stream chat request from userId={}: {}", userId, request.getMessage());
        agentMetrics.recordChatRequest(userId, "stream");

        String systemPrompt = buildSystemPrompt(userId);
        Timer.Sample durationSample = agentMetrics.startTimer();

        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(userId)
                .build();

        response.setBufferSize(0);
        response.setHeader("X-Accel-Buffering", "no");

        StreamingResponseBody body = outputStream -> {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean firstToken = new AtomicBoolean(false);
            Timer.Sample ttftSample = agentMetrics.startTimer();
            AtomicLong tokenCount = new AtomicLong(0);
            long[] startMs = {System.currentTimeMillis()};

            chatClient.prompt()
                    .system(systemPrompt)
                    .user(request.getMessage())
                    .advisors(advisor)
                    .stream()
                    .content()
                    .subscribe(
                            token -> {
                                if (!firstToken.getAndSet(true)) {
                                    agentMetrics.recordTtft(userId, ttftSample);
                                }
                                tokenCount.incrementAndGet();
                                writeSseEvent(outputStream, token);
                            },
                            error -> {
                                log.error("Stream error for userId={}: {}", userId, error.getMessage());
                                agentMetrics.recordLlmError(error.getClass().getSimpleName());
                                latch.countDown();
                            },
                            () -> {
                                // 流式结束，估算 token 速度
                                long elapsedMs = System.currentTimeMillis() - startMs[0];
                                if (elapsedMs > 0 && tokenCount.get() > 0) {
                                    long tps = tokenCount.get() * 1000 / elapsedMs;
                                    agentMetrics.recordTokenSpeed(tps);
                                    // 流式无法精确获取 Usage，估算 output token 数
                                    agentMetrics.recordTokens("deepseek-chat", 0, tokenCount.get());
                                }
                                agentMetrics.recordDuration(userId, durationSample);
                                log.info("Stream completed for userId={}, tokens={}", userId, tokenCount.get());
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

    // buildSystemPrompt 方法保持不变
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
```

- [ ] **Step 3: 验证编译**

Run:
```bash
cd finance-agent && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/metrics/ \
        finance-agent/src/main/java/com/example/agent/controller/ChatController.java
git commit -m "feat: agent 添加指标封装和 ChatController 指标埋点"
```

---

### Task 8: Token 消耗写入 JSONL 文件

**Files:**
- Modify: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java`

在 `/api/chat` 非流式返回后追加写入 JSONL。在 `/api/chat/stream` 流式完成回调中追加写入 JSONL。

- [ ] **Step 1: 在 ChatController 中添加 writeTokenUsage 方法**

在类末尾添加：

```java
private void writeTokenUsage(String userId, long inputTokens, long outputTokens,
                             String model, long durationMs, boolean stream) {
    try {
        new java.io.File("data").mkdirs();
        String record = String.format(
                "{\"timestamp\":\"%s\",\"userId\":\"%s\",\"inputTokens\":%d,\"outputTokens\":%d,\"model\":\"%s\",\"durationMs\":%d,\"stream\":%b}\n",
                java.time.LocalDateTime.now().toString(),
                userId, inputTokens, outputTokens, model, durationMs, stream);
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("data", "token-usage.jsonl"),
                record,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    } catch (IOException e) {
        log.warn("Failed to write token usage: {}", e.getMessage());
    }
}
```

- [ ] **Step 2: 在 chat() 方法中调用**

在 `chat()` 方法的 token 统计代码块中，添加 `writeTokenUsage` 调用。需要先记录 `startMs`：

在方法开头添加 `long startMs = System.currentTimeMillis();`，然后在 usage 统计后调用：
```java
writeTokenUsage(userId, usage.getPromptTokens(), usage.getGenerationTokens(),
        "deepseek-chat", System.currentTimeMillis() - startMs, false);
```

- [ ] **Step 3: 在 chatStream() 的 doOnComplete 中调用**

在流式 `doOnComplete` 回调中，在 token 统计后添加：
```java
writeTokenUsage(userId, 0, tokenCount.get(),
        "deepseek-chat", System.currentTimeMillis() - startMs[0], true);
```

- [ ] **Step 4: 验证编译**

Run:
```bash
cd finance-agent && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/controller/ChatController.java
git commit -m "feat: agent 追加 Token 消耗记录到 token-usage.jsonl 文件"
```

---

### Task 9: LLM 异常降级处理

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 创建 GlobalExceptionHandler.java**

```java
package com.example.agent.exception;

import com.example.agent.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({SocketTimeoutException.class, TimeoutException.class,
            java.net.http.HttpTimeoutException.class})
    public ResponseEntity<ChatResponse> handleTimeout(Exception e) {
        log.warn("LLM 调用超时: {}", e.getMessage());
        return ResponseEntity.ok(new ChatResponse("抱歉，AI 服务响应超时，请稍后重试。"));
    }

    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<ChatResponse> handleRateLimit(Exception e) {
        log.warn("LLM 调用限流: {}", e.getMessage());
        return ResponseEntity.ok(new ChatResponse("抱歉，当前请求过于频繁，请稍后重试。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleGeneral(Exception e) {
        log.error("LLM 调用异常: {}", e.getMessage(), e);
        return ResponseEntity.ok(new ChatResponse("抱歉，AI 服务暂时不可用，请稍后重试。"));
    }
}
```

- [ ] **Step 2: 验证编译**

Run:
```bash
cd finance-agent && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/exception/
git commit -m "feat: agent 添加 LLM 异常降级处理（超时/限流/通用）"
```

---

### Task 10: Context 窗口监控

**Files:**
- Modify: `finance-agent/src/main/java/com/example/agent/memory/JsonFileChatMemory.java`
- Modify: `finance-agent/src/main/java/com/example/agent/config/ChatMemoryConfig.java`

- [ ] **Step 1: 修改 JsonFileChatMemory.java**

将 `LoggerFactory.getLogger` 替换为 `@Slf4j` 注解。添加 `AgentMetrics` 注入。在 `trimAndPersist` 方法中调用 `agentMetrics.updateMemoryGauge()`。

在类级别添加：
```java
import com.example.agent.metrics.AgentMetrics;
```

修改构造函数：
```java
private final AgentMetrics agentMetrics;

public JsonFileChatMemory(String dataDir, int maxMessages, AgentMetrics agentMetrics) {
    this.dataDir = dataDir;
    this.maxMessages = maxMessages;
    this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    this.agentMetrics = agentMetrics;
    new File(dataDir).mkdirs();
}
```

在 `trimAndPersist` 方法末尾添加：
```java
// 更新 Context 监控指标
List<Message> currentMessages = store.get(conversationId);
if (currentMessages != null) {
    int count = currentMessages.size();
    long fileSize = getFile(conversationId).length();
    agentMetrics.updateMemoryGauge(conversationId, count, fileSize);
}
```

- [ ] **Step 2: 修改 ChatMemoryConfig.java**

注入 `AgentMetrics` 并传递给 `JsonFileChatMemory`：

```java
@Bean
public ChatMemory chatMemory(AgentMetrics agentMetrics) {
    return new JsonFileChatMemory(memoryDir, 20, agentMetrics);
}
```

- [ ] **Step 3: 在 System Prompt 中注入 Context 信息**

修改 ChatController 的 `buildSystemPrompt` 方法，添加记忆占用量：
```java
private String buildSystemPrompt(String userId) {
    int memoryCount = chatMemory.get(userId).size();
    String contextInfo = memoryCount > 0
            ? "当前对话记忆: " + memoryCount + " 条 / 上限 20 条"
            : "";

    return """
            你是"小财"，一个智能个人财务助手。
            ...
            当前信息：
            - 用户ID: %s
            - 日期: %s
            - %s
            ...
            """.formatted(userId, java.time.LocalDate.now(), contextInfo);
}
```

- [ ] **Step 4: 验证编译**

Run:
```bash
cd finance-agent && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/memory/ \
        finance-agent/src/main/java/com/example/agent/config/ \
        finance-agent/src/main/java/com/example/agent/controller/
git commit -m "feat: agent 添加 Context 窗口监控（Gauge + System Prompt 注入）"
```

---

### Task 11: 对话反馈接口

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/controller/FeedbackController.java`
- Create: `finance-agent/src/main/java/com/example/agent/dto/FeedbackRequest.java`

- [ ] **Step 1: 创建 FeedbackRequest.java**

```java
package com.example.agent.dto;

public class FeedbackRequest {
    private String userId;
    private String messageId;
    private String rating; // "positive" or "negative"

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getRating() { return rating; }
    public void setRating(String rating) { this.rating = rating; }
}
```

- [ ] **Step 2: 创建 FeedbackController.java**

```java
package com.example.agent.controller;

import com.example.agent.dto.FeedbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class FeedbackController {

    @PostMapping("/feedback")
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) {
        log.info("Feedback received: userId={} messageId={} rating={}",
                request.getUserId(), request.getMessageId(), request.getRating());

        try {
            new java.io.File("data").mkdirs();
            String record = String.format(
                    "{\"timestamp\":\"%s\",\"userId\":\"%s\",\"messageId\":\"%s\",\"rating\":\"%s\"}\n",
                    LocalDateTime.now().toString(),
                    request.getUserId(),
                    request.getMessageId(),
                    request.getRating());
            Files.writeString(
                    Path.of("data", "feedback.jsonl"),
                    record,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write feedback: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", "写入失败"));
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
```

- [ ] **Step 3: 验证编译**

Run:
```bash
cd finance-agent && export JAVA_HOME="/usr/local/opt/openjdk@17" && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add finance-agent/src/main/java/com/example/agent/controller/FeedbackController.java \
        finance-agent/src/main/java/com/example/agent/dto/FeedbackRequest.java
git commit -m "feat: agent 添加对话反馈接口（jsonl 存储）"
```

---

### Task 12: 前端打点、反馈按钮、记忆显示

**Files:**
- Modify: `finance-frontend/src/components/ChatPanel.vue`
- Modify: `finance-frontend/src/components/ChatMessage.vue`

- [ ] **Step 1: 修改 ChatPanel.vue — 添加打点和记忆显示**

在 `send()` 函数中添加 `console.time` 和 TTFT 打点。在 chat-header 区域显示记忆条数。

模板修改：在 chat-header 添加记忆显示：
```html
<div class="chat-header">
  AI 助手
  <span class="memory-info" v-if="memoryCount > 0">记忆: {{ memoryCount }}/20 条</span>
</div>
```

`<script setup>` 中添加：
```js
const memoryCount = ref(0)
```

send 函数修改：
```js
async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  input.value = ''
  messages.value.push({ role: 'user', text })
  const assistantIdx = messages.value.length
  const msgId = 'msg-' + Date.now()
  messages.value.push({ role: 'assistant', text: '', id: msgId })
  thinking.value = true

  console.time('[Agent] 总耗时')
  const requestStart = performance.now()
  let firstToken = false

  try {
    const res = await fetch(`/api/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, userId: userStore.currentUser })
    })

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data:')) {
          if (!firstToken) {
            firstToken = true
            const ttft = performance.now() - requestStart
            console.log('[Agent] TTFT:', Math.round(ttft) + 'ms')
          }
          const payload = line.slice(5)
          const content = payload.startsWith(' ') ? payload.slice(1) : payload
          for (const ch of content) {
            messages.value[assistantIdx].text += ch
            if (msgContainer.value) {
              msgContainer.value.scrollTop = msgContainer.value.scrollHeight
            }
            await new Promise(r => setTimeout(r, 20))
          }
        }
      }
    }
    console.timeEnd('[Agent] 总耗时')
    // 更新记忆条数（user + assistant 成对算）
    memoryCount.value = messages.value.filter(m => m.role === 'user').length * 2
  } catch (e) {
    console.error('[Agent] Error:', e)
    if (!messages.value[assistantIdx]?.text) {
      messages.value[assistantIdx].text = '抱歉，服务暂时不可用'
    }
  } finally {
    thinking.value = false
  }
}
```

CSS 添加：
```css
.memory-info {
  float: right;
  font-size: 0.8rem;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
```

- [ ] **Step 2: 修改 ChatMessage.vue — 添加反馈按钮**

在 AI 消息的气泡下方添加 👍 👎 按钮。只有 assistant 角色且消息有内容（非空）时才显示。

```vue
<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble" v-if="role === 'user'">{{ text }}</div>
    <div v-else>
      <div class="bubble markdown-body" v-html="rendered"></div>
      <div class="feedback-actions" v-if="text && text.length > 0">
        <span class="feedback-btn" :class="{ active: feedback === 'positive' }"
              @click="submitFeedback('positive')">👍</span>
        <span class="feedback-btn" :class="{ active: feedback === 'negative' }"
              @click="submitFeedback('negative')">👎</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { marked } from 'marked'
import { userStore } from '../stores/userStore.js'

const props = defineProps({ role: String, text: String, id: String })

const rendered = computed(() => {
  if (props.role !== 'assistant') return props.text
  return marked.parse(props.text || '')
})

const feedback = ref(null)

async function submitFeedback(rating) {
  if (feedback.value) return // 不能重复投票
  feedback.value = rating
  try {
    await fetch('/api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: userStore.currentUser,
        messageId: props.id,
        rating: rating
      })
    })
  } catch (e) {
    console.error('[Agent] Feedback error:', e)
  }
}
</script>

<style scoped>
.message { display: flex; margin-bottom: 8px; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.bubble {
  max-width: 85%; padding: 8px 12px; border-radius: 12px;
  font-size: 0.9rem; line-height: 1.5;
}
.user .bubble { background: var(--el-color-primary); color: #fff; }
.assistant .bubble { background: var(--el-color-info-light-9); color: #333; }
.markdown-body :deep(table) { width: 100%; border-collapse: collapse; margin: 8px 0; font-size: 0.85rem; }
.markdown-body :deep(th) { background: var(--el-color-primary-light-9); padding: 6px 8px; border: 1px solid #ddd; }
.markdown-body :deep(td) { padding: 4px 8px; border: 1px solid #ddd; }
.markdown-body :deep(code) { background: rgba(0,0,0,0.06); padding: 2px 6px; border-radius: 3px; font-size: 0.85em; }
.markdown-body :deep(strong) { font-weight: 600; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; margin: 4px 0; }
.markdown-body :deep(p) { margin: 4px 0; }
.feedback-actions { margin-top: 4px; text-align: right; }
.feedback-btn { cursor: pointer; margin-left: 8px; opacity: 0.4; font-size: 0.85rem; user-select: none; }
.feedback-btn:hover { opacity: 0.8; }
.feedback-btn.active { opacity: 1; }
</style>
```

- [ ] **Step 3: 验证前端编译**

Run:
```bash
cd finance-frontend && npm run build 2>&1 | tail -5
```
Expected: 无错误输出。

- [ ] **Step 4: Commit**

```bash
git add finance-frontend/src/components/ChatPanel.vue \
        finance-frontend/src/components/ChatMessage.vue
git commit -m "feat: 前端添加 TTFT 打点、对话反馈按钮、记忆条数显示"
```

---

### Task 13: .gitignore 添加 log/ 目录

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: 修改 .gitignore**

在文件末尾添加：
```
log/
```

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: gitignore 添加 log/ 目录"
```

---

## 验证清单

### Per-task verification
- **T1**: `./mvnw compile` 三个服务通过
- **T2**: 启动服务后 `log/` 目录下出现日志文件
- **T3**: `curl /actuator/prometheus` 返回指标数据
- **T4**: 调用 Backend API 后日志中出现 INFO 记录
- **T5**: 调用 Backend API 后 `/actuator/metrics/api.requests.total` 有值
- **T6**: MCP 工具被调用后 `/actuator/metrics/mcp.tool.calls.total` 有值
- **T7**: 对话后 `/actuator/metrics/agent.chat.requests` 有值
- **T8**: 对话后 `finance-agent/data/token-usage.jsonl` 文件生成
- **T9**: 模拟 LLM 超时，返回降级提示而非 500
- **T10**: 多次对话后 `/actuator/metrics/agent.memory.messages` 有值
- **T11**: POST `/api/feedback` 后 `finance-agent/data/feedback.jsonl` 生成
- **T12**: 浏览器 DevTools Console 显示 `[Agent] TTFT: xxxms`
- **T13**: `log/` 目录被 gitignore 排除

### End-to-end smoke test
1. 启动所有服务: `./start-all.sh`
2. 打开 http://localhost:5173
3. 发送聊天消息，观察 Console 输出 TTFT 和总耗时
4. 查看 AI 回复下方的 👍👎 按钮，点击测试
5. 检查日志文件 `log/agent.log` 和 `log/backend.log`
6. 检查指标: `curl http://localhost:8081/actuator/prometheus | grep agent_`
7. 检查 token 消耗: `cat finance-agent/data/token-usage.jsonl`
8. 检查反馈: `cat finance-agent/data/feedback.jsonl`

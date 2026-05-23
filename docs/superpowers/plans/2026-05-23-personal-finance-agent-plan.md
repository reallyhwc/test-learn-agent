# Personal Finance Agent — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个包含记账后端、Vue 前端、AI Agent 和 MCP Server 的个人财务助手 demo 项目

**Architecture:** 4 个独立模块 — finance-backend（SpringBoot + CSV 存储），finance-mcp-server（Spring AI MCP Server，通过 HTTP 调用后端），finance-agent（Spring AI + DeepSeek + MCP Client），finance-frontend（Vue 3 + Vite）。Agent 通过 MCP 协议调用 MCP Server，MCP Server 调记账后端获取数据。

**Tech Stack:** Java 17, Spring Boot 3.4.5, Spring AI 1.1.0, Maven Wrapper, Vue 3 + Vite, DeepSeek API, JUnit 5, Jackson CSV

---

## Phase 0: 环境准备

### Task 1: 安装 Java 17 和 Maven（通过 SDKMAN）

**Files:** None

- [ ] **Step 1: 安装 SDKMAN（如果未安装）**

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

- [ ] **Step 2: 安装 Java 17 和 Maven**

```bash
sdk install java 17.0.14-tem
sdk install maven 3.9.9
```

- [ ] **Step 3: 验证安装**

```bash
java -version
# Expected: openjdk version "17.0.14" ...
mvn -version
# Expected: Apache Maven 3.9.9 ...
```

---

## Phase 1: finance-backend（记账后端）

### Task 2: 创建后端项目结构

**Files:**
- Create: `finance-backend/pom.xml`
- Create: `finance-backend/.mvn/` (Maven Wrapper)
- Create: `finance-backend/src/main/java/com/example/finance/FinanceBackendApplication.java`
- Create: `finance-backend/src/main/resources/application.yml`

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p finance-backend/src/main/java/com/example/finance/{model,repository,service,controller,config}
mkdir -p finance-backend/src/main/resources
mkdir -p finance-backend/src/test/java/com/example/finance/{controller,service}
mkdir -p finance-backend/data
```

- [ ] **Step 2: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>finance-backend</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>finance-backend</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-csv</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 生成 Maven Wrapper**

```bash
cd finance-backend && mvn wrapper:wrapper && cd ..
```

- [ ] **Step 4: 创建 Application 主类**

```java
// finance-backend/src/main/java/com/example/finance/FinanceBackendApplication.java
package com.example.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinanceBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceBackendApplication.class, args);
    }
}
```

- [ ] **Step 5: 创建 application.yml**

```yaml
# finance-backend/src/main/resources/application.yml
server:
  port: 8080

finance:
  data-dir: data
```

- [ ] **Step 6: 验证项目能启动**

```bash
cd finance-backend && ./mvnw spring-boot:run
# Expected: Started FinanceBackendApplication in ... seconds (Ctrl+C to stop)
cd ..
```

- [ ] **Step 7: Commit**

```bash
git add finance-backend/
git commit -m "feat: scaffold finance-backend with Spring Boot 3.4.5 + Maven Wrapper"
```

---

### Task 3: 领域模型和 CSV 数据存储

**Files:**
- Create: `finance-backend/src/main/java/com/example/finance/model/AccountType.java`
- Create: `finance-backend/src/main/java/com/example/finance/model/TransactionType.java`
- Create: `finance-backend/src/main/java/com/example/finance/model/Account.java`
- Create: `finance-backend/src/main/java/com/example/finance/model/Transaction.java`
- Create: `finance-backend/src/main/java/com/example/finance/model/Category.java`
- Create: `finance-backend/src/main/java/com/example/finance/repository/CsvDataStore.java`
- Create: `finance-backend/src/test/java/com/example/finance/service/FinanceServiceTest.java`
- Create: `finance-backend/src/main/java/com/example/finance/service/FinanceService.java`
- Create: `finance-backend/src/main/resources/data/categories.csv`

- [ ] **Step 1: 创建枚举类**

```java
// finance-backend/src/main/java/com/example/finance/model/AccountType.java
package com.example.finance.model;

public enum AccountType {
    CASH, BANK, CARD
}
```

```java
// finance-backend/src/main/java/com/example/finance/model/TransactionType.java
package com.example.finance.model;

public enum TransactionType {
    INCOME, EXPENSE
}
```

- [ ] **Step 2: 创建领域模型 POJO**

```java
// finance-backend/src/main/java/com/example/finance/model/Account.java
package com.example.finance.model;

import java.math.BigDecimal;

public class Account {
    private Long id;
    private String name;
    private AccountType type;
    private BigDecimal balance;

    public Account() {}

    public Account(Long id, String name, AccountType type, BigDecimal balance) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.balance = balance;
    }

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}
```

```java
// finance-backend/src/main/java/com/example/finance/model/Transaction.java
package com.example.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Transaction {
    private Long id;
    private Long accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String category;
    private String note;
    private LocalDate date;

    public Transaction() {}

    public Transaction(Long id, Long accountId, TransactionType type, BigDecimal amount,
                       String category, String note, LocalDate date) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.date = date;
    }

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
}
```

```java
// finance-backend/src/main/java/com/example/finance/model/Category.java
package com.example.finance.model;

public class Category {
    private Long id;
    private String name;
    private TransactionType type;

    public Category() {}

    public Category(Long id, String name, TransactionType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
}
```

- [ ] **Step 3: 创建 CSV 数据存储层**

```java
// finance-backend/src/main/java/com/example/finance/repository/CsvDataStore.java
package com.example.finance.repository;

import com.example.finance.model.*;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class CsvDataStore {

    @Value("${finance.data-dir:data}")
    private String dataDir;

    private final List<Account> accounts = new ArrayList<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    private final AtomicLong accountIdGen = new AtomicLong(1);
    private final AtomicLong transactionIdGen = new AtomicLong(1);
    private final AtomicLong categoryIdGen = new AtomicLong(1);
    private final CsvMapper csvMapper = new CsvMapper();

    @PostConstruct
    public void init() {
        new File(dataDir).mkdirs();
        loadCategories();
        loadAccounts();
        loadTransactions();
    }

    // --- Accounts ---
    public List<Account> findAllAccounts() {
        return new ArrayList<>(accounts);
    }

    public Optional<Account> findAccountById(Long id) {
        return accounts.stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    public Account saveAccount(Account account) {
        if (account.getId() == null) {
            account.setId(accountIdGen.getAndIncrement());
            accounts.add(account);
        } else {
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).getId().equals(account.getId())) {
                    accounts.set(i, account);
                    break;
                }
            }
        }
        persistAccounts();
        return account;
    }

    // --- Transactions ---
    public List<Transaction> findAllTransactions() {
        return new ArrayList<>(transactions);
    }

    public List<Transaction> findTransactions(Long accountId, LocalDate date,
                                               String category, TransactionType type) {
        return transactions.stream()
                .filter(t -> accountId == null || t.getAccountId().equals(accountId))
                .filter(t -> date == null || t.getDate().equals(date))
                .filter(t -> category == null || category.equals(t.getCategory()))
                .filter(t -> type == null || t.getType() == type)
                .collect(Collectors.toList());
    }

    public void saveTransaction(Transaction transaction) {
        if (transaction.getId() == null) {
            transaction.setId(transactionIdGen.getAndIncrement());
            transactions.add(transaction);
            // update account balance
            findAccountById(transaction.getAccountId()).ifPresent(account -> {
                BigDecimal delta = transaction.getType() == TransactionType.INCOME
                        ? transaction.getAmount() : transaction.getAmount().negate();
                account.setBalance(account.getBalance().add(delta));
                persistAccounts();
            });
        }
        persistTransactions();
    }

    // --- Categories ---
    public List<Category> findAllCategories() {
        return new ArrayList<>(categories);
    }

    // --- Persistence ---
    private <T> List<T> loadFromCsv(String filename, Class<T> clazz) {
        File file = new File(dataDir, filename);
        if (!file.exists()) return new ArrayList<>();
        try {
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            MappingIterator<T> it = csvMapper.readerFor(clazz).with(schema).readValues(file);
            return it.readAll();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private <T> void persistToCsv(String filename, List<T> items) {
        try {
            CsvSchema schema = csvMapper.schemaFor(items.isEmpty() ? Object.class : items.get(0).getClass())
                    .withHeader();
            csvMapper.writer(schema).writeValue(new File(dataDir, filename), items);
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist CSV", e);
        }
    }

    private void loadAccounts() {
        accounts.addAll(loadFromCsv("accounts.csv", Account.class));
        accounts.stream().mapToLong(Account::getId).max()
                .ifPresent(max -> accountIdGen.set(max + 1));
        if (accounts.isEmpty()) {
            saveAccount(new Account(null, "默认现金账户", AccountType.CASH, BigDecimal.ZERO));
        }
    }

    private void persistAccounts() {
        persistToCsv("accounts.csv", accounts);
    }

    private void loadTransactions() {
        transactions.addAll(loadFromCsv("transactions.csv", Transaction.class));
        transactions.stream().mapToLong(Transaction::getId).max()
                .ifPresent(max -> transactionIdGen.set(max + 1));
    }

    private void persistTransactions() {
        persistToCsv("transactions.csv", transactions);
    }

    private void loadCategories() {
        categories.addAll(loadFromCsv("categories.csv", Category.class));
        categories.stream().mapToLong(Category::getId).max()
                .ifPresent(max -> categoryIdGen.set(max + 1));
        if (categories.isEmpty()) {
            saveCategory(new Category(null, "工资", TransactionType.INCOME));
            saveCategory(new Category(null, "兼职", TransactionType.INCOME));
            saveCategory(new Category(null, "理财", TransactionType.INCOME));
            saveCategory(new Category(null, "餐饮", TransactionType.EXPENSE));
            saveCategory(new Category(null, "交通", TransactionType.EXPENSE));
            saveCategory(new Category(null, "购物", TransactionType.EXPENSE));
            saveCategory(new Category(null, "房租", TransactionType.EXPENSE));
            saveCategory(new Category(null, "娱乐", TransactionType.EXPENSE));
            saveCategory(new Category(null, "医疗", TransactionType.EXPENSE));
            saveCategory(new Category(null, "其他", TransactionType.EXPENSE));
        }
    }

    private void saveCategory(Category category) {
        if (category.getId() == null) {
            category.setId(categoryIdGen.getAndIncrement());
            categories.add(category);
        }
        persistToCsv("categories.csv", categories);
    }
}
```

- [ ] **Step 4: 创建 FinanceService**

```java
// finance-backend/src/main/java/com/example/finance/service/FinanceService.java
package com.example.finance.service;

import com.example.finance.model.*;
import com.example.finance.repository.CsvDataStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class FinanceService {

    private final CsvDataStore dataStore;

    public FinanceService(CsvDataStore dataStore) {
        this.dataStore = dataStore;
    }

    // Accounts
    public List<Account> listAccounts() {
        return dataStore.findAllAccounts();
    }

    public Account createAccount(Account account) {
        if (account.getBalance() == null) {
            account.setBalance(BigDecimal.ZERO);
        }
        return dataStore.saveAccount(account);
    }

    public Optional<Account> getAccount(Long id) {
        return dataStore.findAccountById(id);
    }

    public Optional<BigDecimal> getBalance(Long accountId) {
        return dataStore.findAccountById(accountId).map(Account::getBalance);
    }

    // Transactions
    public List<Transaction> listTransactions(Long accountId, LocalDate date,
                                               String category, String type) {
        TransactionType tt = null;
        if (type != null && !type.isBlank()) {
            tt = TransactionType.valueOf(type.toUpperCase());
        }
        return dataStore.findTransactions(accountId, date, category, tt);
    }

    public Transaction createTransaction(Transaction transaction) {
        dataStore.saveTransaction(transaction);
        return transaction;
    }

    // Categories
    public List<Category> listCategories() {
        return dataStore.findAllCategories();
    }
}
```

- [ ] **Step 5: 编写 FinanceService 单元测试**

```java
// finance-backend/src/test/java/com/example/finance/service/FinanceServiceTest.java
package com.example.finance.service;

import com.example.finance.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FinanceServiceTest {

    @Autowired
    private FinanceService financeService;

    @Test
    void shouldCreateAccountAndListIt() {
        Account account = new Account(null, "测试账户", AccountType.BANK, BigDecimal.ZERO);
        Account created = financeService.createAccount(account);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("测试账户");
        assertThat(financeService.listAccounts()).isNotEmpty();
    }

    @Test
    void shouldCreateTransactionAndUpdateBalance() {
        Account account = financeService.createAccount(
                new Account(null, "转账测试", AccountType.CASH, new BigDecimal("1000.00")));

        Transaction tx = new Transaction(null, account.getId(), TransactionType.EXPENSE,
                new BigDecimal("200.00"), "餐饮", "午餐", LocalDate.now());
        financeService.createTransaction(tx);

        BigDecimal balance = financeService.getBalance(account.getId()).orElseThrow();
        assertThat(balance).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    @Test
    void shouldListCategories() {
        assertThat(financeService.listCategories()).isNotEmpty();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd finance-backend && ./mvnw test
# Expected: BUILD SUCCESS, all tests green
cd ..
```

- [ ] **Step 7: Commit**

```bash
git add finance-backend/
git commit -m "feat: add domain models, CSV data store, and FinanceService with tests"
```

---

### Task 4: 创建 REST Controller

**Files:**
- Create: `finance-backend/src/main/java/com/example/finance/controller/AccountController.java`
- Create: `finance-backend/src/main/java/com/example/finance/controller/TransactionController.java`
- Create: `finance-backend/src/main/java/com/example/finance/controller/CategoryController.java`
- Create: `finance-backend/src/main/java/com/example/finance/config/WebConfig.java`
- Create: `finance-backend/src/test/java/com/example/finance/controller/AccountControllerTest.java`
- Create: `finance-backend/src/test/java/com/example/finance/controller/TransactionControllerTest.java`

- [ ] **Step 1: 编写失败测试 — AccountControllerTest**

```java
// finance-backend/src/test/java/com/example/finance/controller/AccountControllerTest.java
package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.model.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldListAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void shouldCreateAccount() throws Exception {
        String json = """
            {"name":"测试卡","type":"CARD","balance":5000}
        """;
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("测试卡"));
    }

    @Test
    void shouldGetBalance() throws Exception {
        mockMvc.perform(get("/api/accounts/1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isNumber());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd finance-backend && ./mvnw test -Dtest=AccountControllerTest
# Expected: FAIL — 404 or similar
cd ..
```

- [ ] **Step 3: 实现 AccountController**

```java
// finance-backend/src/main/java/com/example/finance/controller/AccountController.java
package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.service.FinanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final FinanceService financeService;

    public AccountController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Account> listAccounts() {
        return financeService.listAccounts();
    }

    @PostMapping
    public Account createAccount(@RequestBody Account account) {
        return financeService.createAccount(account);
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        return financeService.getBalance(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd finance-backend && ./mvnw test -Dtest=AccountControllerTest
# Expected: PASS
cd ..
```

- [ ] **Step 5: 编写失败测试 — TransactionControllerTest**

```java
// finance-backend/src/test/java/com/example/finance/controller/TransactionControllerTest.java
package com.example.finance.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldListTransactions() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void shouldCreateTransaction() throws Exception {
        String json = """
            {"accountId":1,"type":"EXPENSE","amount":100,"category":"餐饮","note":"午餐","date":"2026-05-23"}
        """;
        mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void shouldFilterTransactionsByDate() throws Exception {
        mockMvc.perform(get("/api/transactions?date=2026-05-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
```

- [ ] **Step 6: 实现 TransactionController 和 CategoryController**

```java
// finance-backend/src/main/java/com/example/finance/controller/TransactionController.java
package com.example.finance.controller;

import com.example.finance.model.Transaction;
import com.example.finance.service.FinanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FinanceService financeService;

    public TransactionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Transaction> listTransactions(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type) {
        return financeService.listTransactions(accountId, date, category, type);
    }

    @PostMapping
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        return financeService.createTransaction(transaction);
    }
}
```

```java
// finance-backend/src/main/java/com/example/finance/controller/CategoryController.java
package com.example.finance.controller;

import com.example.finance.model.Category;
import com.example.finance.service.FinanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final FinanceService financeService;

    public CategoryController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Category> listCategories() {
        return financeService.listCategories();
    }
}
```

- [ ] **Step 7: 添加 CORS 配置**

```java
// finance-backend/src/main/java/com/example/finance/config/WebConfig.java
package com.example.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173")
                        .allowedMethods("*");
            }
        };
    }
}
```

- [ ] **Step 8: 运行所有测试确认通过**

```bash
cd finance-backend && ./mvnw test
# Expected: BUILD SUCCESS, all tests green
cd ..
```

- [ ] **Step 9: Commit**

```bash
git add finance-backend/
git commit -m "feat: add REST controllers with CORS config and integration tests"
```

---

## Phase 2: finance-mcp-server（MCP Server）

### Task 5: 创建 MCP Server 项目结构

**Files:**
- Create: `finance-mcp-server/pom.xml`
- Create: `finance-mcp-server/src/main/java/com/example/mcp/McpServerApplication.java`
- Create: `finance-mcp-server/src/main/resources/application.yml`

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p finance-mcp-server/src/main/java/com/example/mcp/{tool,config,dto}
mkdir -p finance-mcp-server/src/main/resources
mkdir -p finance-mcp-server/src/test/java/com/example/mcp/tool
```

- [ ] **Step 2: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>finance-mcp-server</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>finance-mcp-server</name>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.1.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <releases><enabled>false</enabled></releases>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 生成 Maven Wrapper**

```bash
cd finance-mcp-server && mvn wrapper:wrapper && cd ..
```

- [ ] **Step 4: 创建 Application 和 application.yml**

```java
// finance-mcp-server/src/main/java/com/example/mcp/McpServerApplication.java
package com.example.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
```

```yaml
# finance-mcp-server/src/main/resources/application.yml
server:
  port: 8082

spring:
  ai:
    mcp:
      server:
        name: finance-mcp-server
        version: 1.0.0

finance:
  backend:
    url: http://localhost:8080
```

- [ ] **Step 5: 验证项目能编译**

```bash
cd finance-mcp-server && ./mvnw compile
# Expected: BUILD SUCCESS
cd ..
```

- [ ] **Step 6: Commit**

```bash
git add finance-mcp-server/
git commit -m "feat: scaffold finance-mcp-server with Spring AI MCP Server"
```

---

### Task 6: 实现 MCP Tools

**Files:**
- Create: `finance-mcp-server/src/main/java/com/example/mcp/dto/AccountResponse.java`
- Create: `finance-mcp-server/src/main/java/com/example/mcp/dto/TransactionResponse.java`
- Create: `finance-mcp-server/src/main/java/com/example/mcp/config/RestClientConfig.java`
- Create: `finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java`
- Create: `finance-mcp-server/src/test/java/com/example/mcp/tool/FinanceToolsTest.java`

- [ ] **Step 1: 创建 DTO 类**

```java
// finance-mcp-server/src/main/java/com/example/mcp/dto/AccountResponse.java
package com.example.mcp.dto;

import java.math.BigDecimal;

public class AccountResponse {
    private Long id;
    private String name;
    private String type;
    private BigDecimal balance;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
}
```

```java
// finance-mcp-server/src/main/java/com/example/mcp/dto/TransactionResponse.java
package com.example.mcp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TransactionResponse {
    private Long id;
    private Long accountId;
    private String type;
    private BigDecimal amount;
    private String category;
    private String note;
    private LocalDate date;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
}
```

- [ ] **Step 2: 创建 RestClient 配置**

```java
// finance-mcp-server/src/main/java/com/example/mcp/config/RestClientConfig.java
package com.example.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${finance.backend.url}")
    private String backendUrl;

    @Bean
    public RestClient financeRestClient() {
        return RestClient.create(backendUrl);
    }
}
```

- [ ] **Step 3: 实现 FinanceTools（用 @Tool 注解）**

```java
// finance-mcp-server/src/main/java/com/example/mcp/tool/FinanceTools.java
package com.example.mcp.tool;

import com.example.mcp.dto.AccountResponse;
import com.example.mcp.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class FinanceTools {

    private static final Logger log = LoggerFactory.getLogger(FinanceTools.class);
    private final RestClient restClient;

    public FinanceTools(RestClient restClient) {
        this.restClient = restClient;
    }

    @Tool(description = "查询指定账户的余额")
    public BigDecimal queryBalance(@ToolParam(description = "账户ID") Long accountId) {
        log.info("queryBalance called with accountId={}", accountId);
        return restClient.get()
                .uri("/api/accounts/{id}/balance", accountId)
                .retrieve()
                .body(BigDecimal.class);
    }

    @Tool(description = "查询交易记录列表，可按日期、分类、类型和账户过滤")
    public List<TransactionResponse> listTransactions(
            @ToolParam(description = "交易日期 (yyyy-MM-dd)", required = false) String date,
            @ToolParam(description = "交易分类，如餐饮、交通、购物等", required = false) String category,
            @ToolParam(description = "交易类型: INCOME 或 EXPENSE", required = false) String type,
            @ToolParam(description = "账户ID", required = false) Long accountId) {
        log.info("listTransactions called with date={}, category={}, type={}, accountId={}",
                date, category, type, accountId);

        StringBuilder uri = new StringBuilder("/api/transactions?");
        if (date != null) uri.append("date=").append(date).append("&");
        if (category != null) uri.append("category=").append(category).append("&");
        if (type != null) uri.append("type=").append(type).append("&");
        if (accountId != null) uri.append("accountId=").append(accountId).append("&");

        return List.of(restClient.get()
                .uri(uri.toString())
                .retrieve()
                .body(TransactionResponse[].class));
    }

    @Tool(description = "添加一笔交易记录")
    public Map<String, Object> addTransaction(
            @ToolParam(description = "账户ID") Long accountId,
            @ToolParam(description = "交易类型: INCOME 或 EXPENSE") String type,
            @ToolParam(description = "金额") BigDecimal amount,
            @ToolParam(description = "分类") String category,
            @ToolParam(description = "备注", required = false) String note) {
        log.info("addTransaction called with accountId={}, type={}, amount={}, category={}",
                accountId, type, amount, category);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("accountId", accountId);
        body.put("type", type);
        body.put("amount", amount);
        body.put("category", category);
        body.put("note", note != null ? note : "");
        body.put("date", java.time.LocalDate.now().toString());

        return restClient.post()
                .uri("/api/transactions")
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    @Tool(description = "查询所有账户列表")
    public List<AccountResponse> listAccounts() {
        log.info("listAccounts called");
        return List.of(restClient.get()
                .uri("/api/accounts")
                .retrieve()
                .body(AccountResponse[].class));
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd finance-mcp-server && ./mvnw compile
# Expected: BUILD SUCCESS
cd ..
```

- [ ] **Step 5: Commit**

```bash
git add finance-mcp-server/
git commit -m "feat: implement MCP tools with @Tool annotations calling backend API"
```

---

## Phase 3: finance-agent（Agent 服务）

### Task 7: 创建 Agent 项目结构

**Files:**
- Create: `finance-agent/pom.xml`
- Create: `finance-agent/src/main/java/com/example/agent/AgentApplication.java`
- Create: `finance-agent/src/main/resources/application.yml`
- Create: `finance-agent/src/main/java/com/example/agent/dto/ChatRequest.java`
- Create: `finance-agent/src/main/java/com/example/agent/dto/ChatResponse.java`
- Create: `finance-agent/src/main/java/com/example/agent/config/WebConfig.java`

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p finance-agent/src/main/java/com/example/agent/{controller,dto,config}
mkdir -p finance-agent/src/main/resources
mkdir -p finance-agent/src/test/java/com/example/agent/controller
```

- [ ] **Step 2: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>finance-agent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>finance-agent</name>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.1.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-openai</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
        <repository>
            <id>spring-snapshots</id>
            <name>Spring Snapshots</name>
            <url>https://repo.spring.io/snapshot</url>
            <releases><enabled>false</enabled></releases>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 生成 Maven Wrapper**

```bash
cd finance-agent && mvn wrapper:wrapper && cd ..
```

- [ ] **Step 4: 创建 Application 和配置**

```java
// finance-agent/src/main/java/com/example/agent/AgentApplication.java
package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
```

```yaml
# finance-agent/src/main/resources/application.yml
server:
  port: 8081

spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:your-api-key-here}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
    mcp:
      client:
        connections:
          finance-mcp:
            type: SYNC
            url: http://localhost:8082
```

- [ ] **Step 5: 创建 DTO 和 CORS 配置**

```java
// finance-agent/src/main/java/com/example/agent/dto/ChatRequest.java
package com.example.agent.dto;

public class ChatRequest {
    private String message;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
```

```java
// finance-agent/src/main/java/com/example/agent/dto/ChatResponse.java
package com.example.agent.dto;

public class ChatResponse {
    private String reply;

    public ChatResponse() {}

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
}
```

```java
// finance-agent/src/main/java/com/example/agent/config/WebConfig.java
package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173")
                        .allowedMethods("*");
            }
        };
    }
}
```

- [ ] **Step 6: 验证编译**

```bash
cd finance-agent && ./mvnw compile
# Expected: BUILD SUCCESS
cd ..
```

- [ ] **Step 7: Commit**

```bash
git add finance-agent/
git commit -m "feat: scaffold finance-agent with Spring AI + MCP Client + DeepSeek config"
```

---

### Task 8: 实现 ChatController

**Files:**
- Create: `finance-agent/src/main/java/com/example/agent/controller/ChatController.java`
- Create: `finance-agent/src/test/java/com/example/agent/controller/ChatControllerTest.java`

- [ ] **Step 1: 实现 ChatController**

```java
// finance-agent/src/main/java/com/example/agent/controller/ChatController.java
package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                    你是一个个人财务助手。你可以帮助用户查询账户余额、交易记录，
                    以及添加交易。请用中文回复，简洁明了。
                    金额单位是人民币元。
                    """)
                .build();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("Chat request: {}", request.getMessage());

        String reply = chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();

        return new ChatResponse(reply);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd finance-agent && ./mvnw compile
# Expected: BUILD SUCCESS
cd ..
```

- [ ] **Step 3: Commit**

```bash
git add finance-agent/
git commit -m "feat: implement ChatController with Spring AI ChatClient"
```

---

## Phase 4: finance-frontend（Vue 前端）

### Task 9: 创建 Vue 前端项目

**Files:**
- Create: `finance-frontend/` (Vite + Vue 3 scaffold)
- Modify: `finance-frontend/src/App.vue`
- Create: `finance-frontend/src/components/AppHeader.vue`
- Create: `finance-frontend/src/components/AccountList.vue`
- Create: `finance-frontend/src/components/TransactionList.vue`
- Create: `finance-frontend/src/components/TransactionForm.vue`
- Create: `finance-frontend/src/components/ChatPanel.vue`
- Create: `finance-frontend/src/components/ChatMessage.vue`

- [ ] **Step 1: 用 Vite 创建 Vue 3 项目**

```bash
npm create vite@latest finance-frontend -- --template vue
cd finance-frontend && npm install && cd ..
```

- [ ] **Step 2: 创建 AppHeader.vue**

```html
<!-- finance-frontend/src/components/AppHeader.vue -->
<template>
  <header class="app-header">
    <h1>Personal Finance Agent</h1>
    <span class="subtitle">记账 · AI 助手</span>
  </header>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  background: #1a1a2e;
  color: #fff;
}
.app-header h1 {
  margin: 0;
  font-size: 1.2rem;
}
.subtitle {
  color: #888;
  font-size: 0.85rem;
}
</style>
```

- [ ] **Step 3: 创建 AccountList.vue**

```html
<!-- finance-frontend/src/components/AccountList.vue -->
<template>
  <div class="account-list">
    <h3>账户</h3>
    <div v-if="loading">加载中...</div>
    <div v-else class="accounts">
      <div v-for="account in accounts" :key="account.id" class="account-card">
        <span class="account-type">{{ typeLabel(account.type) }}</span>
        <span class="account-name">{{ account.name }}</span>
        <span class="account-balance" :class="{ negative: account.balance < 0 }">
          ¥{{ account.balance.toFixed(2) }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const accounts = ref([])
const loading = ref(true)

const API_BASE = 'http://localhost:8080'

const typeLabel = (t) => ({ CASH: '现金', BANK: '储蓄', CARD: '信用' }[t] || t)

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/accounts`)
    accounts.value = await res.json()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.accounts { display: flex; gap: 12px; flex-wrap: wrap; }
.account-card {
  flex: 1; min-width: 180px; padding: 16px;
  background: #f5f5f5; border-radius: 8px;
  display: flex; flex-direction: column; gap: 4px;
}
.account-type { font-size: 0.8rem; color: #888; }
.account-name { font-weight: 600; }
.account-balance { font-size: 1.2rem; font-weight: 700; color: #2ecc71; }
.account-balance.negative { color: #e74c3c; }
</style>
```

- [ ] **Step 4: 创建 TransactionForm.vue**

```html
<!-- finance-frontend/src/components/TransactionForm.vue -->
<template>
  <div class="tx-form">
    <h3>记一笔</h3>
    <form @submit.prevent="submit">
      <select v-model="form.accountId" required>
        <option value="">选择账户</option>
        <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>
      <select v-model="form.type" required>
        <option value="">类型</option>
        <option value="INCOME">收入</option>
        <option value="EXPENSE">支出</option>
      </select>
      <input v-model.number="form.amount" type="number" step="0.01" placeholder="金额" required />
      <select v-model="form.category" required>
        <option value="">分类</option>
        <option v-for="c in categories" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <input v-model="form.note" placeholder="备注（可选）" />
      <button type="submit" :disabled="submitting">保存</button>
    </form>
    <p v-if="msg" class="msg">{{ msg }}</p>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'

const emit = defineEmits(['saved'])
const API_BASE = 'http://localhost:8080'
const accounts = ref([])
const categories = ref([])
const submitting = ref(false)
const msg = ref('')

const form = reactive({
  accountId: '', type: '', amount: null, category: '', note: ''
})

onMounted(async () => {
  const [aRes, cRes] = await Promise.all([
    fetch(`${API_BASE}/api/accounts`),
    fetch(`${API_BASE}/api/categories`)
  ])
  accounts.value = await aRes.json()
  categories.value = await cRes.json()
})

async function submit() {
  submitting.value = true
  msg.value = ''
  try {
    const res = await fetch(`${API_BASE}/api/transactions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...form,
        date: new Date().toISOString().split('T')[0]
      })
    })
    if (res.ok) {
      msg.value = '保存成功'
      form.amount = null; form.note = ''; form.category = ''
      emit('saved')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tx-form { margin-bottom: 20px; }
form { display: flex; gap: 8px; flex-wrap: wrap; }
select, input, button { padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
button { background: #2ecc71; color: #fff; border: none; cursor: pointer; }
button:disabled { background: #95a5a6; }
.msg { color: #2ecc71; font-size: 0.85rem; }
</style>
```

- [ ] **Step 5: 创建 TransactionList.vue**

```html
<!-- finance-frontend/src/components/TransactionList.vue -->
<template>
  <div class="tx-list">
    <h3>交易记录</h3>
    <div class="filters">
      <input v-model="filterDate" type="date" @change="fetchList" />
      <select v-model="filterCategory" @change="fetchList">
        <option value="">全部分类</option>
        <option v-for="c in categories" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
    </div>
    <div v-if="loading">加载中...</div>
    <table v-else>
      <thead>
        <tr><th>日期</th><th>分类</th><th>类型</th><th>金额</th><th>备注</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in transactions" :key="t.id">
          <td>{{ t.date }}</td>
          <td>{{ t.category }}</td>
          <td :class="t.type">{{ t.type === 'INCOME' ? '收入' : '支出' }}</td>
          <td :class="t.type">¥{{ t.amount.toFixed(2) }}</td>
          <td>{{ t.note }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const API_BASE = 'http://localhost:8080'
const transactions = ref([])
const categories = ref([])
const loading = ref(true)
const filterDate = ref('')
const filterCategory = ref('')

onMounted(async () => {
  const [txRes, cRes] = await Promise.all([
    fetch(`${API_BASE}/api/transactions`),
    fetch(`${API_BASE}/api/categories`)
  ])
  transactions.value = await txRes.json()
  categories.value = await cRes.json()
  loading.value = false
})

async function fetchList() {
  loading.value = true
  let url = `${API_BASE}/api/transactions?`
  if (filterDate.value) url += `date=${filterDate.value}&`
  if (filterCategory.value) url += `category=${filterCategory.value}&`
  const res = await fetch(url)
  transactions.value = await res.json()
  loading.value = false
}

defineExpose({ fetchList })
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 12px; }
.filters input, .filters select { padding: 6px; border: 1px solid #ddd; border-radius: 4px; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 8px 12px; border-bottom: 1px solid #eee; text-align: left; }
.INCOME { color: #2ecc71; }
.EXPENSE { color: #e74c3c; }
</style>
```

- [ ] **Step 6: 创建 ChatPanel.vue 和 ChatMessage.vue**

```html
<!-- finance-frontend/src/components/ChatMessage.vue -->
<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble">{{ text }}</div>
  </div>
</template>

<script setup>
defineProps({ role: String, text: String })
</script>

<style scoped>
.message { display: flex; margin-bottom: 8px; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.bubble {
  max-width: 80%; padding: 8px 12px; border-radius: 12px;
  font-size: 0.9rem; line-height: 1.4;
}
.user .bubble { background: #3498db; color: #fff; }
.assistant .bubble { background: #f0f0f0; color: #333; }
</style>
```

```html
<!-- finance-frontend/src/components/ChatPanel.vue -->
<template>
  <div class="chat-panel">
    <div class="chat-header">AI 助手</div>
    <div class="chat-messages" ref="msgContainer">
      <ChatMessage v-for="(m, i) in messages" :key="i" :role="m.role" :text="m.text" />
      <div v-if="thinking" class="thinking">思考中...</div>
    </div>
    <div class="chat-input">
      <input v-model="input" @keyup.enter="send" placeholder="比如：我的余额是多少？" :disabled="thinking" />
      <button @click="send" :disabled="thinking">发送</button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import ChatMessage from './ChatMessage.vue'

const AGENT_BASE = 'http://localhost:8081'
const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  input.value = ''
  messages.value.push({ role: 'user', text })
  thinking.value = true
  try {
    const res = await fetch(`${AGENT_BASE}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text })
    })
    const data = await res.json()
    messages.value.push({ role: 'assistant', text: data.reply })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: '抱歉，服务暂时不可用' })
  } finally {
    thinking.value = false
    await nextTick()
    msgContainer.value?.scrollTo({ top: msgContainer.value.scrollHeight, behavior: 'smooth' })
  }
}
</script>

<style scoped>
.chat-panel {
  display: flex; flex-direction: column; height: 100%;
  border-left: 1px solid #eee; background: #fafafa;
}
.chat-header { padding: 12px 16px; font-weight: 600; border-bottom: 1px solid #eee; }
.chat-messages { flex: 1; overflow-y: auto; padding: 12px; }
.chat-input { display: flex; padding: 12px; gap: 8px; border-top: 1px solid #eee; }
.chat-input input { flex: 1; padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
.chat-input button { padding: 8px 16px; background: #3498db; color: #fff; border: none; border-radius: 4px; cursor: pointer; }
.thinking { color: #888; font-size: 0.85rem; font-style: italic; }
</style>
```

- [ ] **Step 7: 更新 App.vue 集成所有组件**

```html
<!-- finance-frontend/src/App.vue -->
<template>
  <div class="app">
    <AppHeader />
    <div class="main-layout">
      <div class="content-area">
        <AccountList />
        <TransactionForm @saved="refreshTx" />
        <TransactionList ref="txList" />
      </div>
      <div class="chat-area">
        <ChatPanel />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import AccountList from './components/AccountList.vue'
import TransactionForm from './components/TransactionForm.vue'
import TransactionList from './components/TransactionList.vue'
import ChatPanel from './components/ChatPanel.vue'

const txList = ref(null)
function refreshTx() { txList.value?.fetchList() }
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.app { display: flex; flex-direction: column; min-height: 100vh; }
.main-layout { display: flex; flex: 1; }
.content-area { flex: 1; padding: 20px; min-width: 0; }
.chat-area { width: 380px; min-height: calc(100vh - 56px); }
</style>
```

- [ ] **Step 8: 验证前端能启动**

```bash
cd finance-frontend && npm run dev
# Expected: Vite dev server running at http://localhost:5173
# Ctrl+C to stop
cd ..
```

- [ ] **Step 9: Commit**

```bash
git add finance-frontend/
git commit -m "feat: implement Vue 3 frontend with accounting and chat panels"
```

---

## Phase 5: 集成 & 文档

### Task 10: 创建启动脚本和 README

**Files:**
- Create: `start-all.sh`
- Create: `README.md`

- [ ] **Step 1: 创建 start-all.sh**

```bash
#!/bin/bash
# start-all.sh — 一键启动所有服务
set -e

echo "=== Personal Finance Agent ==="
echo ""

# Start backend
echo "[1/4] Starting finance-backend (:8080)..."
cd finance-backend
./mvnw spring-boot:run -q &
BACKEND_PID=$!
cd ..
sleep 8

# Start MCP Server
echo "[2/4] Starting finance-mcp-server (:8082)..."
cd finance-mcp-server
./mvnw spring-boot:run -q &
MCP_PID=$!
cd ..
sleep 5

# Start Agent
echo "[3/4] Starting finance-agent (:8081)..."
cd finance-agent
./mvnw spring-boot:run -q &
AGENT_PID=$!
cd ..
sleep 5

# Start Frontend
echo "[4/4] Starting finance-frontend (:5173)..."
cd finance-frontend
npm run dev &
FRONTEND_PID=$!
cd ..

echo ""
echo "=== All services started ==="
echo "Frontend:  http://localhost:5173"
echo "Backend:   http://localhost:8080"
echo "Agent:     http://localhost:8081"
echo "MCP Server: http://localhost:8082"
echo ""
echo "Press Ctrl+C to stop all services"

trap "kill $BACKEND_PID $MCP_PID $AGENT_PID $FRONTEND_PID 2>/dev/null; exit 0" SIGINT SIGTERM
wait
```

```bash
chmod +x start-all.sh
```

- [ ] **Step 2: 创建 README.md**

```markdown
# Personal Finance Agent

一个基于 Java + Spring AI 的个人记账助手，集成了 AI 对话查询和 MCP 服务。

## 项目简介

这是一个学习型 demo 项目，在 Java 生态体系下实践 AI Agent 和 MCP 协议。

### 功能

- **记账管理**：账户管理、交易录入、分类筛选
- **AI 对话**：通过自然语言查询余额、交易记录，支持添加交易
- **MCP 服务**：对外暴露标准 MCP Tool，可供 Claude Desktop 等客户端接入

### 架构

```
Vue 前端 (:5173)
    ├── HTTP REST → 记账后端 (:8080)
    └── HTTP REST → Agent 服务 (:8081)
                        │ MCP 协议 (SSE)
                        ▼
                    MCP Server (:8082)
                        │ HTTP REST
                        ▼
                    记账后端 (:8080)
```

### 技术栈

| 模块 | 技术 |
|------|------|
| 记账后端 | Spring Boot 3.4 + Java 17 + CSV 存储 |
| MCP Server | Spring AI 1.1 + MCP Server WebMVC |
| Agent | Spring AI 1.1 + DeepSeek + MCP Client |
| 前端 | Vue 3 + Vite |

### 快速开始

#### 环境要求

- Java 17+
- Node.js 18+
- DeepSeek API Key ([获取地址](https://platform.deepseek.com/api_keys))

#### 启动

```bash
# 1. 设置 DeepSeek API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 2. 一键启动
./start-all.sh

# 3. 打开浏览器
open http://localhost:5173
```

#### 手动启动

```bash
# 终端 1: 记账后端
cd finance-backend && ./mvnw spring-boot:run

# 终端 2: MCP Server
cd finance-mcp-server && ./mvnw spring-boot:run

# 终端 3: Agent
cd finance-agent && ./mvnw spring-boot:run

# 终端 4: 前端
cd finance-frontend && npm install && npm run dev
```

### MCP 接入

在 Claude Desktop 或其他 MCP 客户端中配置：

```json
{
  "mcpServers": {
    "finance": {
      "url": "http://localhost:8082/sse"
    }
  }
}
```

### License

MIT
```

- [ ] **Step 3: Commit**

```bash
git add start-all.sh README.md
git commit -m "docs: add README and one-click start script"
```

---

## 自审清单

| 检查项 | 结果 |
|--------|------|
| 所有任务有明确的文件路径 | PASS |
| 所有任务有可执行的代码 | PASS |
| 所有测试有 expected 输出 | PASS |
| 模型/类型/配置在任务间一致 | PASS |
| 无 TBD / TODO / placeholder | PASS |

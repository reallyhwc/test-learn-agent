package com.example.finance.repository;

import com.example.finance.dto.PageResult;
import com.example.finance.model.*;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
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

    private final CsvMapper csvMapper = CsvMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final CsvSchema ACCOUNT_SCHEMA = CsvSchema.builder()
            .addColumn("id")
            .addColumn("name")
            .addColumn("type")
            .addColumn("balance")
            .addColumn("userId")
            .build().withHeader();

    private static final CsvSchema ACCOUNT_SCHEMA_OLD = CsvSchema.builder()
            .addColumn("id")
            .addColumn("name")
            .addColumn("type")
            .addColumn("balance")
            .build().withHeader();

    private static final CsvSchema TRANSACTION_SCHEMA = CsvSchema.builder()
            .addColumn("id")
            .addColumn("accountId")
            .addColumn("type")
            .addColumn("amount")
            .addColumn("category")
            .addColumn("note")
            .addColumn("date")
            .addColumn("userId")
            .build().withHeader();

    private static final CsvSchema TRANSACTION_SCHEMA_OLD = CsvSchema.builder()
            .addColumn("id")
            .addColumn("accountId")
            .addColumn("type")
            .addColumn("amount")
            .addColumn("category")
            .addColumn("note")
            .addColumn("date")
            .build().withHeader();

    private static final CsvSchema CATEGORY_SCHEMA = CsvSchema.builder()
            .addColumn("id")
            .addColumn("name")
            .addColumn("type")
            .build().withHeader();

    @PostConstruct
    public void init() {
        log.info("初始化 CsvDataStore，数据目录: {}", dataDir);
        new File(dataDir).mkdirs();
        loadCategories();
        loadAccounts();
        loadTransactions();
    }

    // ---- Account operations ----

    public List<Account> findAllAccounts() {
        return new ArrayList<>(accounts);
    }

    public List<Account> findAllAccountsByUserId(String userId) {
        if (userId == null || userId.isBlank()) return findAllAccounts();
        return accounts.stream()
                .filter(a -> userId.equals(a.getUserId()))
                .collect(Collectors.toList());
    }

    public Optional<Account> findAccountById(Long id) {
        return accounts.stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    public Account saveAccount(Account account) {
        if (account.getId() == null) {
            account.setId(accountIdGen.getAndIncrement());
            if (account.getUserId() == null) account.setUserId("default");
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

    // ---- Transaction operations ----

    public List<Transaction> findAllTransactions() {
        return new ArrayList<>(transactions);
    }

    public List<Transaction> findTransactions(Long accountId, LocalDate date,
                                               String category, TransactionType type, String userId) {
        return transactions.stream()
                .filter(t -> accountId == null || t.getAccountId().equals(accountId))
                .filter(t -> date == null || (t.getDate() != null && t.getDate().equals(date)))
                .filter(t -> category == null || category.equals(t.getCategory()))
                .filter(t -> type == null || t.getType() == type)
                .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                .collect(Collectors.toList());
    }

    public PageResult<Transaction> findTransactionsPaginated(Long accountId, LocalDate date,
            String category, TransactionType type, String userId, int page, int pageSize) {
        List<Transaction> filtered = transactions.stream()
                .filter(t -> accountId == null || t.getAccountId().equals(accountId))
                .filter(t -> date == null || (t.getDate() != null && t.getDate().equals(date)))
                .filter(t -> category == null || category.equals(t.getCategory()))
                .filter(t -> type == null || t.getType() == type)
                .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                .collect(Collectors.toList());

        long total = filtered.size();
        int fromIndex = (page - 1) * pageSize;
        if (fromIndex >= total) return new PageResult<>(List.of(), page, pageSize, total);

        int toIndex = Math.min(fromIndex + pageSize, (int) total);
        List<Transaction> pageItems = filtered.subList(fromIndex, toIndex);
        return new PageResult<>(pageItems, page, pageSize, total);
    }

    public void saveTransaction(Transaction transaction) {
        if (transaction.getId() == null) {
            transaction.setId(transactionIdGen.getAndIncrement());
            if (transaction.getUserId() == null) transaction.setUserId("default");
            transactions.add(transaction);
            findAccountById(transaction.getAccountId()).ifPresent(account -> {
                BigDecimal delta = transaction.getType() == TransactionType.INCOME
                        ? transaction.getAmount() : transaction.getAmount().negate();
                account.setBalance(account.getBalance().add(delta));
                persistAccounts();
            });
            persistTransactions();
        } else {
            throw new UnsupportedOperationException("Updating existing transactions is not supported");
        }
    }

    // ---- Category operations ----

    public List<Category> findAllCategories() {
        return new ArrayList<>(categories);
    }

    // ---- CSV persistence helpers ----

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

    private <T> void persistToCsv(String filename, List<T> items, CsvSchema schema) {
        try {
            csvMapper.writer(schema).writeValue(new File(dataDir, filename), items);
        } catch (IOException e) {
            log.error("持久化 CSV 文件失败: {}", filename, e);
            throw new RuntimeException("Failed to persist CSV: " + filename, e);
        }
    }

    private boolean csvHasUserIdColumn(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String header = reader.readLine();
            return header != null && header.contains("userId");
        } catch (IOException e) {
            return false;
        }
    }

    private void loadAccounts() {
        File file = new File(dataDir, "accounts.csv");
        if (!file.exists()) {
            saveAccount(new Account(null, "默认现金账户", AccountType.CASH, BigDecimal.ZERO, "default"));
            return;
        }
        if (csvHasUserIdColumn(file)) {
            List<Account> loaded = loadFromCsv("accounts.csv", Account.class, ACCOUNT_SCHEMA);
            accounts.addAll(loaded);
        } else {
            List<Account> loaded = loadFromCsv("accounts.csv", Account.class, ACCOUNT_SCHEMA_OLD);
            loaded.forEach(a -> a.setUserId("default"));
            accounts.addAll(loaded);
            persistAccounts();
        }
        accounts.stream().mapToLong(Account::getId).max()
                .ifPresent(max -> accountIdGen.set(max + 1));
    }

    private void persistAccounts() {
        persistToCsv("accounts.csv", accounts, ACCOUNT_SCHEMA);
    }

    private void loadTransactions() {
        File file = new File(dataDir, "transactions.csv");
        if (!file.exists()) return;
        if (csvHasUserIdColumn(file)) {
            List<Transaction> loaded = loadFromCsv("transactions.csv", Transaction.class, TRANSACTION_SCHEMA);
            transactions.addAll(loaded);
        } else {
            List<Transaction> loaded = loadFromCsv("transactions.csv", Transaction.class, TRANSACTION_SCHEMA_OLD);
            loaded.forEach(t -> t.setUserId("default"));
            transactions.addAll(loaded);
            persistTransactions();
        }
        transactions.stream().mapToLong(Transaction::getId).max()
                .ifPresent(max -> transactionIdGen.set(max + 1));
    }

    private void persistTransactions() {
        persistToCsv("transactions.csv", transactions, TRANSACTION_SCHEMA);
    }

    private void loadCategories() {
        List<Category> loaded = loadFromCsv("categories.csv", Category.class, CATEGORY_SCHEMA);
        if (loaded.isEmpty()) {
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
            return;
        }
        categories.addAll(loaded);
        categories.stream().mapToLong(Category::getId).max()
                .ifPresent(max -> categoryIdGen.set(max + 1));
    }

    private void saveCategory(Category category) {
        if (category.getId() == null) {
            category.setId(categoryIdGen.getAndIncrement());
            categories.add(category);
        }
        persistToCsv("categories.csv", categories, CATEGORY_SCHEMA);
    }
}

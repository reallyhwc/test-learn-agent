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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    /** 账户ID到列表索引的映射，加速按ID查找 */
    private final Map<Long, Integer> accountIdIndex = new ConcurrentHashMap<>();
    /** 读写锁保护内存数据与 CSV 文件的并发安全 */
    private final ReadWriteLock dataLock = new ReentrantReadWriteLock();

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

    // ---- Account index ----

    /** 重建账户ID索引，在初始化或数据变更后调用 */
    private void rebuildAccountIndex() {
        accountIdIndex.clear();
        for (int i = 0; i < accounts.size(); i++) {
            accountIdIndex.put(accounts.get(i).getId(), i);
        }
    }

    // ---- Account operations ----

    public List<Account> findAllAccounts() {
        dataLock.readLock().lock();
        try {
            return new ArrayList<>(accounts);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public List<Account> findAllAccountsByUserId(String userId) {
        dataLock.readLock().lock();
        try {
            if (userId == null || userId.isBlank()) return new ArrayList<>(accounts);
            return accounts.stream()
                    .filter(a -> userId.equals(a.getUserId()))
                    .collect(Collectors.toList());
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public Optional<Account> findAccountById(Long id) {
        dataLock.readLock().lock();
        try {
            Integer index = accountIdIndex.get(id);
            if (index != null && index < accounts.size()) {
                Account account = accounts.get(index);
                if (account.getId().equals(id)) {
                    return Optional.of(account);
                }
            }
            // 索引未命中时回退到遍历
            return accounts.stream().filter(a -> a.getId().equals(id)).findFirst();
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public Account saveAccount(Account account) {
        dataLock.writeLock().lock();
        try {
            if (account.getId() == null) {
                account.setId(accountIdGen.getAndIncrement());
                if (account.getUserId() == null) account.setUserId("default");
                accounts.add(account);
                accountIdIndex.put(account.getId(), accounts.size() - 1);
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
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    // ---- Transaction operations ----

    public List<Transaction> findAllTransactions() {
        dataLock.readLock().lock();
        try {
            return new ArrayList<>(transactions);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public List<Transaction> findTransactions(Long accountId, LocalDate startDate, LocalDate endDate,
                                               String category, TransactionType type, String userId) {
        dataLock.readLock().lock();
        try {
            return transactions.stream()
                    .filter(t -> accountId == null || accountId.equals(t.getAccountId()))
                    .filter(t -> matchDateRange(t.getDate(), startDate, endDate))
                    .filter(t -> category == null || category.equals(t.getCategory()))
                    .filter(t -> type == null || t.getType() == type)
                    .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                    .collect(Collectors.toList());
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public PageResult<Transaction> findTransactionsPaginated(Long accountId, LocalDate startDate, LocalDate endDate,
            String category, TransactionType type, String userId, int page, int pageSize) {
        dataLock.readLock().lock();
        try {
            List<Transaction> filtered = transactions.stream()
                    .filter(t -> accountId == null || accountId.equals(t.getAccountId()))
                    .filter(t -> matchDateRange(t.getDate(), startDate, endDate))
                    .filter(t -> category == null || category.equals(t.getCategory()))
                    .filter(t -> type == null || t.getType() == type)
                    .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                    .collect(Collectors.toList());

            long total = filtered.size();
            int fromIndex = Math.max(0, (page - 1) * pageSize);
            if (fromIndex >= total) return new PageResult<>(List.of(), page, pageSize, total);

            int toIndex = Math.min(fromIndex + pageSize, (int) total);
            List<Transaction> pageItems = new ArrayList<>(filtered.subList(fromIndex, toIndex));
            return new PageResult<>(pageItems, page, pageSize, total);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    /**
     * 保存交易记录，余额更新和持久化在同一个写锁内完成，保证原子性。
     */
    public void saveTransaction(Transaction transaction) {
        dataLock.writeLock().lock();
        try {
            if (transaction.getId() == null) {
                transaction.setId(transactionIdGen.getAndIncrement());
                if (transaction.getUserId() == null) transaction.setUserId("default");
                transactions.add(transaction);
                // 余额更新与持久化在同一个写锁内，保证原子性
                accounts.stream()
                        .filter(a -> a.getId().equals(transaction.getAccountId()))
                        .findFirst()
                        .ifPresent(account -> {
                            BigDecimal delta = transaction.getType() == TransactionType.INCOME
                                    ? transaction.getAmount() : transaction.getAmount().negate();
                            account.setBalance(account.getBalance().add(delta));
                        });
                // 一次性持久化两个文件，减少中间状态窗口
                persistAccounts();
                persistTransactions();
            } else {
                throw new UnsupportedOperationException("不支持更新已有交易");
            }
        } finally {
            dataLock.writeLock().unlock();
        }
    }

    private boolean matchDateRange(LocalDate txDate, LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) return true;
        if (txDate == null) return false;
        if (startDate != null && txDate.isBefore(startDate)) return false;
        if (endDate != null && txDate.isAfter(endDate)) return false;
        return true;
    }

    // ---- Category operations ----

    public List<Category> findAllCategories() {
        dataLock.readLock().lock();
        try {
            return new ArrayList<>(categories);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    // ---- CSV persistence helpers ----

    private <T> List<T> loadFromCsv(String filename, Class<T> clazz, CsvSchema schema) {
        File file = new File(dataDir, filename);
        if (!file.exists()) return new ArrayList<>();
        try {
            MappingIterator<T> it = csvMapper.readerFor(clazz).with(schema).readValues(file);
            return it.readAll();
        } catch (Exception e) {
            log.error("加载 CSV 文件失败: {}，将使用空数据启动", filename, e);
            try {
                java.nio.file.Path csvPath = file.toPath();
                java.nio.file.Path backup = csvPath.resolveSibling(csvPath.getFileName() + ".corrupted." + System.currentTimeMillis());
                java.nio.file.Files.move(csvPath, backup);
                log.warn("已备份损坏文件到: {}", backup);
            } catch (Exception backupEx) {
                log.error("备份损坏文件失败", backupEx);
            }
            return new ArrayList<>();
        }
    }

    private <T> void persistToCsv(String filename, List<T> items, CsvSchema schema) {
        try {
            File targetFile = new File(dataDir, filename);
            File tempFile = new File(dataDir, filename + ".tmp");
            csvMapper.writer(schema).writeValue(tempFile, items);
            // 原子重命名，减少数据损坏风险
            if (!tempFile.renameTo(targetFile)) {
                // renameTo 失败时回退到直接写入
                csvMapper.writer(schema).writeValue(targetFile, items);
                tempFile.delete();
            }
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
        rebuildAccountIndex();
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

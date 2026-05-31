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

/**
 * 【CSV 文件数据存储】
 *
 * <p>以内存 + CSV 文件实现数据持久化，无需数据库。启动时从 CSV 加载数据，
 * 写入时同时更新内存和持久化到文件。
 *
 * <h3>Schema 兼容策略</h3>
 * 每个实体（Account、Transaction、Category）维护多套 Schema 常量，
 * 加载时按新→旧顺序尝试，确保旧格式 CSV 文件可正常读取。
 *
 * <h3>并发安全</h3>
 * 使用 {@link ReadWriteLock}：读操作共享锁，写操作独占锁，余额更新与
 * 持久化在同一个写锁内完成，保证原子性。
 *
 * @see com.example.finance.service.FinanceService 服务层
 */
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
            .addColumn("subCategory")
            .addColumn("note")
            .addColumn("date")
            .addColumn("userId")
            .build().withHeader();

    /** 无 subCategory 列的旧交易格式（含 userId） */
    private static final CsvSchema TRANSACTION_SCHEMA_NO_SUB = CsvSchema.builder()
            .addColumn("id")
            .addColumn("accountId")
            .addColumn("type")
            .addColumn("amount")
            .addColumn("category")
            .addColumn("note")
            .addColumn("date")
            .addColumn("userId")
            .build().withHeader();

    /** 无 subCategory 且无 userId 的最旧交易格式 */
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
            .addColumn("parentId")
            .build().withHeader();

    /** 无 parentId 列的旧分类格式 */
    private static final CsvSchema CATEGORY_SCHEMA_OLD = CsvSchema.builder()
            .addColumn("id")
            .addColumn("name")
            .addColumn("type")
            .build().withHeader();

    /**
     * 应用启动时初始化：创建数据目录，从 CSV 文件加载分类、账户和交易数据到内存。
     */
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

    /**
     * 返回全部账户的副本（读锁保护）。
     */
    public List<Account> findAllAccounts() {
        dataLock.readLock().lock();
        try {
            return new ArrayList<>(accounts);
        } finally {
            dataLock.readLock().unlock();
        }
    }

    /**
     * 按 userId 筛选账户列表。userId 为空时返回全部。
     */
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

    /**
     * 按 ID 查找账户。优先使用 {@code accountIdIndex} 索引 O(1) 查找，索引未命中时回退到遍历。
     */
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

    /**
     * 保存账户（新增或更新）。新增时自动分配 ID，userId 默认为 "default"。
     * 更新后立即持久化到 CSV 文件。
     */
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
                                               String category, String subCategory,
                                               TransactionType type, String userId) {
        dataLock.readLock().lock();
        try {
            return transactions.stream()
                    .filter(t -> accountId == null || accountId.equals(t.getAccountId()))
                    .filter(t -> matchDateRange(t.getDate(), startDate, endDate))
                    .filter(t -> category == null || category.equals(t.getCategory()))
                    .filter(t -> subCategory == null || subCategory.equals(t.getSubCategory()))
                    .filter(t -> type == null || t.getType() == type)
                    .filter(t -> userId == null || userId.isBlank() || userId.equals(t.getUserId()))
                    .collect(Collectors.toList());
        } finally {
            dataLock.readLock().unlock();
        }
    }

    public PageResult<Transaction> findTransactionsPaginated(Long accountId, LocalDate startDate, LocalDate endDate,
            String category, String subCategory,
            TransactionType type, String userId, int page, int pageSize) {
        dataLock.readLock().lock();
        try {
            List<Transaction> filtered = transactions.stream()
                    .filter(t -> accountId == null || accountId.equals(t.getAccountId()))
                    .filter(t -> matchDateRange(t.getDate(), startDate, endDate))
                    .filter(t -> category == null || category.equals(t.getCategory()))
                    .filter(t -> subCategory == null || subCategory.equals(t.getSubCategory()))
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

    /** 一级分类 → 默认二级分类的映射，用于历史数据推导 */
    private static final Map<String, String> DEFAULT_SUB_CATEGORY = Map.ofEntries(
            Map.entry("餐饮", "日常餐饮"), Map.entry("交通", "日常出行"),
            Map.entry("购物", "日用品"), Map.entry("房租", "房租"),
            Map.entry("娱乐", "日常娱乐"), Map.entry("医疗", "门诊"),
            Map.entry("其他", "其他支出"), Map.entry("工资", "基本工资"),
            Map.entry("兼职", "兼职收入"), Map.entry("理财", "利息"),
            Map.entry("居住", "日常居住"), Map.entry("社交", "聚会")
    );

    private void loadTransactions() {
        File file = new File(dataDir, "transactions.csv");
        if (!file.exists()) return;

        boolean hasUserId = csvHasUserIdColumn(file);
        boolean hasSubCategory = csvHasColumn(file, "subCategory");

        if (hasUserId && hasSubCategory) {
            // 最新格式：含 userId + subCategory
            List<Transaction> loaded = loadFromCsv("transactions.csv", Transaction.class, TRANSACTION_SCHEMA);
            transactions.addAll(loaded);
        } else if (hasUserId) {
            // 中间格式：含 userId 但无 subCategory
            List<Transaction> loaded = loadFromCsv("transactions.csv", Transaction.class, TRANSACTION_SCHEMA_NO_SUB);
            loaded.forEach(this::inferSubCategory);
            transactions.addAll(loaded);
            persistTransactions();
            log.info("已为 {} 条历史交易推导补全 subCategory", loaded.size());
        } else {
            // 最旧格式：无 userId 无 subCategory
            List<Transaction> loaded = loadFromCsv("transactions.csv", Transaction.class, TRANSACTION_SCHEMA_OLD);
            loaded.forEach(t -> {
                t.setUserId("default");
                inferSubCategory(t);
            });
            transactions.addAll(loaded);
            persistTransactions();
        }
        transactions.stream().mapToLong(Transaction::getId).max()
                .ifPresent(max -> transactionIdGen.set(max + 1));
    }

    /** 根据一级分类推导二级分类 */
    private void inferSubCategory(Transaction transaction) {
        if (transaction.getSubCategory() != null && !transaction.getSubCategory().isBlank()) {
            return;
        }
        String category = transaction.getCategory();
        String defaultSub = (category != null) ? DEFAULT_SUB_CATEGORY.getOrDefault(category, category) : "未分类";
        transaction.setSubCategory(defaultSub);
    }

    private void persistTransactions() {
        persistToCsv("transactions.csv", transactions, TRANSACTION_SCHEMA);
    }

    private boolean csvHasColumn(File file, String columnName) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String header = reader.readLine();
            return header != null && header.contains(columnName);
        } catch (IOException e) {
            return false;
        }
    }

    private void loadCategories() {
        File file = new File(dataDir, "categories.csv");
        if (file.exists() && csvHasColumn(file, "parentId")) {
            List<Category> loaded = loadFromCsv("categories.csv", Category.class, CATEGORY_SCHEMA);
            if (!loaded.isEmpty()) {
                categories.addAll(loaded);
                categories.stream().mapToLong(Category::getId).max()
                        .ifPresent(max -> categoryIdGen.set(max + 1));
                return;
            }
        } else if (file.exists()) {
            log.info("检测到旧格式 categories.csv（无 parentId 列），将重建为二级分类");
        }
        // 初始化或升级：创建二级树形种子数据
        categories.clear();
        initCategorySeedData();
    }

    private void initCategorySeedData() {
        // 收入一级分类
        long salaryId = addCategory("工资", TransactionType.INCOME, null);
        addCategory("基本工资", TransactionType.INCOME, salaryId);
        addCategory("奖金", TransactionType.INCOME, salaryId);
        addCategory("补贴", TransactionType.INCOME, salaryId);

        long partTimeId = addCategory("兼职", TransactionType.INCOME, null);
        addCategory("兼职收入", TransactionType.INCOME, partTimeId);

        long investId = addCategory("理财", TransactionType.INCOME, null);
        addCategory("利息", TransactionType.INCOME, investId);
        addCategory("分红", TransactionType.INCOME, investId);
        addCategory("基金", TransactionType.INCOME, investId);

        // 支出一级分类
        long foodId = addCategory("餐饮", TransactionType.EXPENSE, null);
        addCategory("外卖", TransactionType.EXPENSE, foodId);
        addCategory("食堂", TransactionType.EXPENSE, foodId);
        addCategory("聚餐", TransactionType.EXPENSE, foodId);
        addCategory("日常餐饮", TransactionType.EXPENSE, foodId);

        long transportId = addCategory("交通", TransactionType.EXPENSE, null);
        addCategory("公交", TransactionType.EXPENSE, transportId);
        addCategory("打车", TransactionType.EXPENSE, transportId);
        addCategory("加油", TransactionType.EXPENSE, transportId);
        addCategory("日常出行", TransactionType.EXPENSE, transportId);

        long shoppingId = addCategory("购物", TransactionType.EXPENSE, null);
        addCategory("日用品", TransactionType.EXPENSE, shoppingId);
        addCategory("服饰", TransactionType.EXPENSE, shoppingId);
        addCategory("数码", TransactionType.EXPENSE, shoppingId);

        long rentId = addCategory("房租", TransactionType.EXPENSE, null);
        addCategory("房租", TransactionType.EXPENSE, rentId);
        addCategory("物业", TransactionType.EXPENSE, rentId);
        addCategory("水电", TransactionType.EXPENSE, rentId);

        long funId = addCategory("娱乐", TransactionType.EXPENSE, null);
        addCategory("电影", TransactionType.EXPENSE, funId);
        addCategory("游戏", TransactionType.EXPENSE, funId);
        addCategory("旅行", TransactionType.EXPENSE, funId);

        long medicalId = addCategory("医疗", TransactionType.EXPENSE, null);
        addCategory("门诊", TransactionType.EXPENSE, medicalId);
        addCategory("药品", TransactionType.EXPENSE, medicalId);
        addCategory("体检", TransactionType.EXPENSE, medicalId);

        long otherId = addCategory("其他", TransactionType.EXPENSE, null);
        addCategory("其他支出", TransactionType.EXPENSE, otherId);

        persistCategories();
    }

    /** 添加分类到内存列表，返回其 ID */
    private long addCategory(String name, TransactionType type, Long parentId) {
        long id = categoryIdGen.getAndIncrement();
        categories.add(new Category(id, name, type, parentId));
        return id;
    }

    private void saveCategory(Category category) {
        if (category.getId() == null) {
            category.setId(categoryIdGen.getAndIncrement());
            categories.add(category);
        }
        persistCategories();
    }

    private void persistCategories() {
        persistToCsv("categories.csv", categories, CATEGORY_SCHEMA);
    }
}

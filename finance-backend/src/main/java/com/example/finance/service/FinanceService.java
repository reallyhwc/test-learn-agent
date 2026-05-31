package com.example.finance.service;

import com.example.finance.dto.PageResult;
import com.example.finance.model.*;
import com.example.finance.repository.CsvDataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 财务服务层。
 * 当前使用 CsvDataStore 内存存储，无需额外缓存层。
 * 如果未来切换为数据库存储，可在查询方法上添加 @Cacheable 注解。
 */
@Slf4j
@Service
public class FinanceService {

    private final CsvDataStore dataStore;

    public FinanceService(CsvDataStore dataStore) {
        this.dataStore = dataStore;
    }

    /**
     * 查询指定用户的全部账户列表。
     *
     * @param userId 用户标识
     * @return 账户列表
     */
    // 切换数据库后可添加 @Cacheable("accounts")
    public List<Account> listAccounts(String userId) {
        return dataStore.findAllAccountsByUserId(userId);
    }

    /**
     * 创建新账户。校验 name、type 非空，初始余额默认 0。
     *
     * @param account 账户实体
     * @return 创建成功的账户（含自动生成的 id）
     * @throws IllegalArgumentException 如 name、type 为空或余额为负
     */
    public Account createAccount(Account account) {
        if (account.getName() == null || account.getName().isBlank()) {
            throw new IllegalArgumentException("账户名称不能为空");
        }
        if (account.getType() == null) {
            throw new IllegalArgumentException("账户类型不能为空");
        }
        if (account.getBalance() == null) {
            account.setBalance(BigDecimal.ZERO);
        }
        if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("账户初始余额不能为负数");
        }
        return dataStore.saveAccount(account);
    }

    /**
     * 按 ID 查询单个账户。
     *
     * @param id 账户 ID
     * @return 账户（如存在），空 Optional（如不存在）
     */
    public Optional<Account> getAccount(Long id) {
        return dataStore.findAccountById(id);
    }

    /**
     * 查询账户的当前余额。
     *
     * @param accountId 账户 ID
     * @return 余额（如账户存在），空 Optional（如不存在）
     */
    public Optional<BigDecimal> getBalance(Long accountId) {
        return dataStore.findAccountById(accountId).map(Account::getBalance);
    }

    /**
     * 查询交易明细（不分页）。支持按账户、日期、分类、类型多条件筛选。
     *
     * @param userId 用户标识
     * @param accountId 账户 ID 筛选（可选）
     * @param startDate 起始日期（可选）
     * @param endDate 结束日期（可选）
     * @param category 一级分类筛选（可选）
     * @param subCategory 二级分类筛选（可选）
     * @param type 交易类型（INCOME/EXPENSE，可选）
     * @return 符合条件的交易列表
     */
    public List<Transaction> listTransactions(String userId, Long accountId, LocalDate startDate,
                                               LocalDate endDate, String category,
                                               String subCategory, String type) {
        TransactionType tt = parseTransactionType(type);
        return dataStore.findTransactions(accountId, startDate, endDate, category, subCategory, tt, userId);
    }

    public PageResult<Transaction> listTransactionsPaginated(String userId, Long accountId,
            LocalDate startDate, LocalDate endDate, String category, String subCategory,
            String type, int page, int pageSize) {
        TransactionType tt = parseTransactionType(type);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 1000) pageSize = 1000;
        return dataStore.findTransactionsPaginated(accountId, startDate, endDate, category, subCategory, tt, userId, page, pageSize);
    }

    /**
     * 按分类汇总交易统计。
     * @param groupBy 分组维度："category"（一级分类，默认）或 "subCategory"（二级分类）
     */
    public List<Map<String, Object>> summarizeTransactions(String userId, String type,
                                                            LocalDate startDate, LocalDate endDate,
                                                            String groupBy) {
        TransactionType tt = parseTransactionType(type);
        List<Transaction> transactions = dataStore.findTransactions(null, startDate, endDate, null, null, tt, userId);

        boolean bySubCategory = "subCategory".equalsIgnoreCase(groupBy);
        Map<String, BigDecimal> totalByGroup = new LinkedHashMap<>();
        Map<String, Integer> countByGroup = new LinkedHashMap<>();

        for (Transaction t : transactions) {
            String groupKey;
            if (bySubCategory) {
                groupKey = t.getSubCategory() != null ? t.getSubCategory() : "未分类";
            } else {
                groupKey = t.getCategory() != null ? t.getCategory() : "未分类";
            }
            BigDecimal amount = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            totalByGroup.merge(groupKey, amount, BigDecimal::add);
            countByGroup.merge(groupKey, 1, Integer::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        String groupLabel = bySubCategory ? "subCategory" : "category";
        for (Map.Entry<String, BigDecimal> entry : totalByGroup.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(groupLabel, entry.getKey());
            item.put("totalAmount", entry.getValue());
            item.put("count", countByGroup.get(entry.getKey()));
            result.add(item);
            grandTotal = grandTotal.add(entry.getValue());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(groupLabel, "合计");
        summary.put("totalAmount", grandTotal);
        summary.put("count", transactions.size());
        result.add(summary);

        return result;
    }

    private TransactionType parseTransactionType(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return TransactionType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的交易类型: " + type + "，仅支持 INCOME 或 EXPENSE");
        }
    }

    /**
     * 创建交易记录。校验所有必填字段后写入，自动补齐日期（如未提供）。
     * 同时更新关联账户的余额。
     *
     * @param transaction 交易实体（含 accountId、type、amount、category 等）
     * @return 创建成功的交易记录（含自动生成的 id）
     * @throws IllegalArgumentException 如必填字段缺失或账户不存在
     */
    public Transaction createTransaction(Transaction transaction) {
        // 参数校验 — 防止脏数据写入
        if (transaction.getAccountId() == null) {
            throw new IllegalArgumentException("账户ID不能为空");
        }
        if (transaction.getType() == null) {
            throw new IllegalArgumentException("交易类型不能为空");
        }
        if (transaction.getAmount() == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        if (transaction.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("金额必须大于零");
        }
        if (transaction.getCategory() == null || transaction.getCategory().isBlank()) {
            throw new IllegalArgumentException("分类不能为空");
        }
        if (transaction.getSubCategory() == null || transaction.getSubCategory().isBlank()) {
            throw new IllegalArgumentException("二级分类不能为空");
        }
        if (transaction.getDate() == null) {
            transaction.setDate(LocalDate.now());
        }
        // 校验账户是否存在
        if (dataStore.findAccountById(transaction.getAccountId()).isEmpty()) {
            throw new IllegalArgumentException("账户ID不存在: " + transaction.getAccountId());
        }

        log.info("创建交易: userId={} type={} amount={} category={}",
                transaction.getUserId(), transaction.getType(), transaction.getAmount(), transaction.getCategory());
        dataStore.saveTransaction(transaction);
        return transaction;
    }

    /**
     * 返回树形分类列表：一级分类带 children 列表。
     */
    public List<Map<String, Object>> listCategoriesTree() {
        List<Category> all = dataStore.findAllCategories();
        // 按 parentId 分组
        Map<Long, List<Category>> childrenMap = new LinkedHashMap<>();
        List<Category> roots = new ArrayList<>();
        for (Category c : all) {
            if (c.getParentId() == null) {
                roots.add(c);
            } else {
                childrenMap.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category root : roots) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", root.getId());
            node.put("name", root.getName());
            node.put("type", root.getType().name());
            List<Map<String, Object>> children = new ArrayList<>();
            List<Category> subs = childrenMap.getOrDefault(root.getId(), List.of());
            for (Category sub : subs) {
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("id", sub.getId());
                child.put("name", sub.getName());
                children.add(child);
            }
            node.put("children", children);
            result.add(node);
        }
        return result;
    }
}

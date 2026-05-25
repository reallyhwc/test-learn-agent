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

    // 切换数据库后可添加 @Cacheable("accounts")
    public List<Account> listAccounts(String userId) {
        return dataStore.findAllAccountsByUserId(userId);
    }

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

    public Optional<Account> getAccount(Long id) {
        return dataStore.findAccountById(id);
    }

    public Optional<BigDecimal> getBalance(Long accountId) {
        return dataStore.findAccountById(accountId).map(Account::getBalance);
    }

    public List<Transaction> listTransactions(String userId, Long accountId, LocalDate startDate,
                                               LocalDate endDate, String category, String type) {
        TransactionType tt = parseTransactionType(type);
        return dataStore.findTransactions(accountId, startDate, endDate, category, tt, userId);
    }

    public PageResult<Transaction> listTransactionsPaginated(String userId, Long accountId,
            LocalDate startDate, LocalDate endDate, String category, String type, int page, int pageSize) {
        TransactionType tt = parseTransactionType(type);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 1000) pageSize = 1000;
        return dataStore.findTransactionsPaginated(accountId, startDate, endDate, category, tt, userId, page, pageSize);
    }

    public List<Map<String, Object>> summarizeTransactions(String userId, String type,
                                                            LocalDate startDate, LocalDate endDate) {
        TransactionType tt = parseTransactionType(type);
        List<Transaction> transactions = dataStore.findTransactions(null, startDate, endDate, null, tt, userId);

        Map<String, BigDecimal> totalByCategory = new LinkedHashMap<>();
        Map<String, Integer> countByCategory = new LinkedHashMap<>();

        for (Transaction t : transactions) {
            String category = t.getCategory() != null ? t.getCategory() : "未分类";
            BigDecimal amount = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            totalByCategory.merge(category, amount, BigDecimal::add);
            countByCategory.merge(category, 1, Integer::sum);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : totalByCategory.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", entry.getKey());
            item.put("totalAmount", entry.getValue());
            item.put("count", countByCategory.get(entry.getKey()));
            result.add(item);
            grandTotal = grandTotal.add(entry.getValue());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", "合计");
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

    public List<Category> listCategories() {
        return dataStore.findAllCategories();
    }
}

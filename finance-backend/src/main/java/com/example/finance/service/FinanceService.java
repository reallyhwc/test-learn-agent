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

    public List<Transaction> listTransactions(String userId, Long accountId, LocalDate date,
                                               String category, String type) {
        TransactionType tt = parseTransactionType(type);
        return dataStore.findTransactions(accountId, date, category, tt, userId);
    }

    // 切换数据库后可添加 @Cacheable("transactions")
    public PageResult<Transaction> listTransactionsPaginated(String userId, Long accountId,
            LocalDate date, String category, String type, int page, int pageSize) {
        TransactionType tt = parseTransactionType(type);
        // 分页参数边界校验
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 20;
        if (pageSize > 1000) pageSize = 1000;
        return dataStore.findTransactionsPaginated(accountId, date, category, tt, userId, page, pageSize);
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

package com.example.finance.service;

import com.example.finance.config.TestDataConfig;
import com.example.finance.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinanceServiceTest {

    @Autowired
    private FinanceService financeService;

    @Autowired
    private TestDataConfig testDataConfig;

    @BeforeEach
    void setUp() {
        testDataConfig.backup("accounts.csv");
        testDataConfig.backup("transactions.csv");
        testDataConfig.resetAll();
    }

    @Test
    void shouldCreateAccountAndListIt() {
        Account account = new Account(null, "测试账户", AccountType.BANK, BigDecimal.ZERO, "testuser");
        Account created = financeService.createAccount(account);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("测试账户");
        assertThat(created.getUserId()).isEqualTo("testuser");
        assertThat(financeService.listAccounts("testuser")).isNotEmpty();
    }

    @Test
    void shouldCreateTransactionAndUpdateBalance() {
        Account account = financeService.createAccount(
                new Account(null, "转账测试", AccountType.CASH, new BigDecimal("1000.00"), "testuser"));
        Transaction tx = new Transaction(null, account.getId(), TransactionType.EXPENSE,
                new BigDecimal("200.00"), "餐饮", "午餐", LocalDate.now(), "testuser");
        financeService.createTransaction(tx);
        BigDecimal balance = financeService.getBalance(account.getId()).orElseThrow();
        assertThat(balance).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    @Test
    void shouldListCategories() {
        assertThat(financeService.listCategories()).isNotEmpty();
    }

    @Test
    void shouldIsolateDataByUserId() {
        String uniqueUser = "isoTestUser_" + System.currentTimeMillis();
        Account a1 = new Account(null, "用户A账户", AccountType.CASH, BigDecimal.ZERO, uniqueUser);
        Account a2 = new Account(null, "用户B账户", AccountType.CASH, BigDecimal.ZERO, uniqueUser + "_2");
        financeService.createAccount(a1);
        financeService.createAccount(a2);

        List<Account> userAAccounts = financeService.listAccounts(uniqueUser);
        assertThat(userAAccounts).isNotEmpty();
        assertThat(userAAccounts.get(0).getUserId()).isEqualTo(uniqueUser);
        assertThat(userAAccounts).allMatch(a -> a.getUserId().equals(uniqueUser));

        List<Account> userBAccounts = financeService.listAccounts(uniqueUser + "_2");
        assertThat(userBAccounts).isNotEmpty();
        assertThat(userBAccounts.get(0).getUserId()).isEqualTo(uniqueUser + "_2");
    }

    @Test
    void shouldCalculateBalanceCorrectly() {
        Account account = new Account(null, "测试", AccountType.CASH, new BigDecimal("1000"), "test");
        account = financeService.createAccount(account);
        Transaction t1 = new Transaction(null, account.getId(), TransactionType.EXPENSE,
                new BigDecimal("200"), "餐饮", "午餐", null, "test");
        Transaction t2 = new Transaction(null, account.getId(), TransactionType.INCOME,
                new BigDecimal("500"), "工资", null, null, "test");
        financeService.createTransaction(t1);
        financeService.createTransaction(t2);
        BigDecimal balance = financeService.getBalance(account.getId()).orElse(BigDecimal.ZERO);
        assertThat(balance).isEqualByComparingTo(new BigDecimal("1300"));
    }
}

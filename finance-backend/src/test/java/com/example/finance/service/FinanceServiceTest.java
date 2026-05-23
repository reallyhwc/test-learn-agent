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
}

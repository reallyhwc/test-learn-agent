package com.example.finance.service;

import com.example.finance.config.TestDataConfig;
import com.example.finance.dto.PageResult;
import com.example.finance.model.*;
import com.example.finance.repository.CsvDataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CsvDataStoreTest {

    @Autowired
    private CsvDataStore dataStore;

    @Autowired
    private TestDataConfig testDataConfig;

    @BeforeEach
    void setUp() {
        testDataConfig.backup("accounts.csv");
        testDataConfig.backup("transactions.csv");
        testDataConfig.resetAll();
    }

    @Test
    void shouldLoadAccountsWithUserId() {
        List<Account> accounts = dataStore.findAllAccountsByUserId("default");
        assertThat(accounts).isNotEmpty();
        accounts.forEach(a -> assertThat(a.getUserId()).isEqualTo("default"));
    }

    @Test
    void shouldLoadAccountsForZhangsan() {
        List<Account> accounts = dataStore.findAllAccountsByUserId("zhangsan");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).getName()).isEqualTo("张三钱包");
    }

    @Test
    void shouldLoadTransactionsWithCorrectSchema() {
        List<Transaction> txns = dataStore.findAllTransactions();
        assertThat(txns).isNotEmpty();
        txns.forEach(t -> assertThat(t.getUserId()).isNotNull());
    }

    @Test
    void shouldAutoGenerateUniqueIds() {
        Account a1 = new Account(null, "测试1", AccountType.CASH, BigDecimal.ZERO, "test");
        Account a2 = new Account(null, "测试2", AccountType.CASH, BigDecimal.ZERO, "test");
        Account r1 = dataStore.saveAccount(a1);
        Account r2 = dataStore.saveAccount(a2);
        assertThat(r1.getId()).isNotNull();
        assertThat(r2.getId()).isNotNull();
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    @Test
    void shouldSaveAndReloadAccount() {
        Account account = new Account(null, "持久化测试", AccountType.BANK, new BigDecimal("500"), "test");
        Account saved = dataStore.saveAccount(account);
        assertThat(saved.getId()).isNotNull();
        List<Account> accounts = dataStore.findAllAccountsByUserId("test");
        assertThat(accounts).anyMatch(a -> a.getName().equals("持久化测试"));
    }

    @Test
    void shouldFindAccountById() {
        Optional<Account> acc = dataStore.findAccountById(1L);
        assertThat(acc).isPresent();
        assertThat(acc.get().getId()).isEqualTo(1L);
    }

    @Test
    void shouldReturnEmptyForMissingAccount() {
        Optional<Account> acc = dataStore.findAccountById(99999L);
        assertThat(acc).isEmpty();
    }

    @Test
    void shouldPaginateTransactionsCorrectly() {
        PageResult<Transaction> page1 = dataStore.findTransactionsPaginated(
                null, null, null, null, null, "default", 1, 3);
        assertThat(page1.getItems()).hasSize(3);
        assertThat(page1.getPage()).isEqualTo(1);
        assertThat(page1.getTotalPages()).isGreaterThan(0);
    }
}

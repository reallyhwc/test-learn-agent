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

    public List<Category> listCategories() {
        return dataStore.findAllCategories();
    }
}

package com.example.finance.repository;

import com.example.finance.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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

    @PostConstruct
    public void init() {
        new File(dataDir).mkdirs();
        loadCategories();
        loadAccounts();
        loadTransactions();
    }

    // ---- Account operations ----

    public List<Account> findAllAccounts() {
        return new ArrayList<>(accounts);
    }

    public Optional<Account> findAccountById(Long id) {
        return accounts.stream().filter(a -> a.getId().equals(id)).findFirst();
    }

    public Account saveAccount(Account account) {
        if (account.getId() == null) {
            account.setId(accountIdGen.getAndIncrement());
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
                                               String category, TransactionType type) {
        return transactions.stream()
                .filter(t -> accountId == null || t.getAccountId().equals(accountId))
                .filter(t -> date == null || t.getDate().equals(date))
                .filter(t -> category == null || category.equals(t.getCategory()))
                .filter(t -> type == null || t.getType() == type)
                .collect(Collectors.toList());
    }

    public void saveTransaction(Transaction transaction) {
        if (transaction.getId() == null) {
            transaction.setId(transactionIdGen.getAndIncrement());
            transactions.add(transaction);
            findAccountById(transaction.getAccountId()).ifPresent(account -> {
                BigDecimal delta = transaction.getType() == TransactionType.INCOME
                        ? transaction.getAmount() : transaction.getAmount().negate();
                account.setBalance(account.getBalance().add(delta));
                persistAccounts();
            });
        }
        persistTransactions();
    }

    // ---- Category operations ----

    public List<Category> findAllCategories() {
        return new ArrayList<>(categories);
    }

    // ---- CSV persistence helpers ----

    private void loadAccounts() {
        File file = new File(dataDir, "accounts.csv");
        if (!file.exists()) {
            saveAccount(new Account(null, "默认现金账户", AccountType.CASH, BigDecimal.ZERO));
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                Account account = new Account(
                        Long.parseLong(parts[0]),
                        parts[1],
                        AccountType.valueOf(parts[2]),
                        new BigDecimal(parts[3])
                );
                accounts.add(account);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load accounts.csv", e);
        }
        accounts.stream().mapToLong(Account::getId).max()
                .ifPresent(max -> accountIdGen.set(max + 1));
    }

    private void persistAccounts() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dataDir, "accounts.csv")))) {
            writer.write("id,name,type,balance");
            writer.newLine();
            for (Account a : accounts) {
                writer.write(a.getId() + "," + a.getName() + ","
                        + a.getType().name() + "," + a.getBalance().toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist accounts.csv", e);
        }
    }

    private void loadTransactions() {
        File file = new File(dataDir, "transactions.csv");
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                Transaction tx = new Transaction(
                        Long.parseLong(parts[0]),
                        Long.parseLong(parts[1]),
                        TransactionType.valueOf(parts[2]),
                        new BigDecimal(parts[3]),
                        parts[4],
                        parts[5],
                        LocalDate.parse(parts[6])
                );
                transactions.add(tx);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load transactions.csv", e);
        }
        transactions.stream().mapToLong(Transaction::getId).max()
                .ifPresent(max -> transactionIdGen.set(max + 1));
    }

    private void persistTransactions() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dataDir, "transactions.csv")))) {
            writer.write("id,accountId,type,amount,category,note,date");
            writer.newLine();
            for (Transaction t : transactions) {
                writer.write(t.getId() + "," + t.getAccountId() + ","
                        + t.getType().name() + "," + t.getAmount().toString() + ","
                        + t.getCategory() + "," + (t.getNote() != null ? t.getNote() : "") + ","
                        + t.getDate().toString());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist transactions.csv", e);
        }
    }

    private void loadCategories() {
        File file = new File(dataDir, "categories.csv");
        if (!file.exists()) {
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
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                Category category = new Category(
                        Long.parseLong(parts[0]),
                        parts[1],
                        TransactionType.valueOf(parts[2])
                );
                categories.add(category);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load categories.csv", e);
        }
        categories.stream().mapToLong(Category::getId).max()
                .ifPresent(max -> categoryIdGen.set(max + 1));
    }

    private void saveCategory(Category category) {
        if (category.getId() == null) {
            category.setId(categoryIdGen.getAndIncrement());
            categories.add(category);
        }
        persistToCsv("categories.csv", categories);
    }

    private <T> void persistToCsv(String filename, List<T> items) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(new File(dataDir, filename)))) {
            if (filename.equals("categories.csv")) {
                writer.write("id,name,type");
                writer.newLine();
                for (T item : items) {
                    Category c = (Category) item;
                    writer.write(c.getId() + "," + c.getName() + "," + c.getType().name());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist " + filename, e);
        }
    }
}

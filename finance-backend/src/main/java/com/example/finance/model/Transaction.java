package com.example.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Transaction {
    private Long id;
    private Long accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String category;
    private String note;
    private LocalDate date;
    private String userId;

    public Transaction() {}

    public Transaction(Long id, Long accountId, TransactionType type, BigDecimal amount,
                       String category, String note, LocalDate date, String userId) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.category = category;
        this.note = note;
        this.date = date;
        this.userId = userId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public TransactionType getType() { return type; }
    public void setType(TransactionType type) { this.type = type; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

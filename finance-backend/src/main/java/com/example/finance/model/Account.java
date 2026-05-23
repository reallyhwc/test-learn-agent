package com.example.finance.model;

import java.math.BigDecimal;

public class Account {
    private Long id;
    private String name;
    private AccountType type;
    private BigDecimal balance;
    private String userId;

    public Account() {}

    public Account(Long id, String name, AccountType type, BigDecimal balance, String userId) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.balance = balance;
        this.userId = userId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

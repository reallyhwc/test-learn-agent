package com.example.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    private Long id;
    private Long accountId;
    private TransactionType type;
    private BigDecimal amount;
    private String category;
    private String note;
    private LocalDate date;
    private String userId;
}

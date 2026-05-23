package com.example.finance.controller;

import com.example.finance.model.Transaction;
import com.example.finance.service.FinanceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FinanceService financeService;

    public TransactionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Transaction> listTransactions(
            @RequestParam(required = false, defaultValue = "default") String userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type) {
        return financeService.listTransactions(userId, accountId, date, category, type);
    }

    @PostMapping
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        return financeService.createTransaction(transaction);
    }
}

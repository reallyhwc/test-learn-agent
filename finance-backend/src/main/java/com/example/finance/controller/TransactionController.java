package com.example.finance.controller;

import com.example.finance.dto.PageResult;
import com.example.finance.model.Transaction;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FinanceService financeService;

    public TransactionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public PageResult<Transaction> listTransactions(
            @RequestParam(required = false, defaultValue = "default") String userId,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        log.info("GET /api/transactions userId={} page={} pageSize={}", userId, page, pageSize);
        return financeService.listTransactionsPaginated(userId, accountId, date, category, type, page, pageSize);
    }

    @PostMapping
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        log.info("POST /api/transactions userId={} amount={} category={}", transaction.getUserId(), transaction.getAmount(), transaction.getCategory());
        return financeService.createTransaction(transaction);
    }
}

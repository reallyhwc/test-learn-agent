package com.example.finance.controller;

import com.example.finance.dto.PageResult;
import com.example.finance.model.Transaction;
import com.example.finance.service.FinanceService;
import com.example.finance.util.LogMaskUtils;
import com.example.finance.util.XssUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        // 分页参数边界校验
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 20;
        if (pageSize > 100) pageSize = 100;

        log.info("GET /api/transactions userId={} page={} pageSize={}", LogMaskUtils.maskUserId(userId), page, pageSize);
        return financeService.listTransactionsPaginated(userId, accountId, date, category, type, page, pageSize);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        log.info("POST /api/transactions userId={} amount={} category={}", LogMaskUtils.maskUserId(transaction.getUserId()), LogMaskUtils.maskAmount(transaction.getAmount()), transaction.getCategory());
        // XSS 清洗 note 字段
        if (transaction.getNote() != null) {
            transaction.setNote(XssUtils.sanitize(transaction.getNote()));
        }
        return financeService.createTransaction(transaction);
    }
}

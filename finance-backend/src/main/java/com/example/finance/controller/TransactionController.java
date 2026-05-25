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
import java.util.Map;

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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subCategory,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        if (page == null || page < 1) page = 1;
        if (pageSize == null || pageSize < 1) pageSize = 20;
        if (pageSize > 1000) pageSize = 1000;

        log.info("GET /api/transactions userId={} page={} pageSize={}", LogMaskUtils.maskUserId(userId), page, pageSize);
        return financeService.listTransactionsPaginated(userId, accountId, startDate, endDate, category, subCategory, type, page, pageSize);
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summarizeTransactions(
            @RequestParam(required = false, defaultValue = "default") String userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "category") String groupBy) {
        log.info("GET /api/transactions/summary userId={} type={} groupBy={}", LogMaskUtils.maskUserId(userId), type, groupBy);
        return financeService.summarizeTransactions(userId, type, startDate, endDate, groupBy);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction createTransaction(@RequestBody Transaction transaction) {
        log.info("POST /api/transactions userId={} amount={} category={}/{}", LogMaskUtils.maskUserId(transaction.getUserId()), LogMaskUtils.maskAmount(transaction.getAmount()), transaction.getCategory(), transaction.getSubCategory());
        // XSS 清洗用户可控文本字段
        if (transaction.getNote() != null) {
            transaction.setNote(XssUtils.sanitize(transaction.getNote()));
        }
        if (transaction.getCategory() != null) {
            transaction.setCategory(XssUtils.sanitize(transaction.getCategory()));
        }
        if (transaction.getSubCategory() != null) {
            transaction.setSubCategory(XssUtils.sanitize(transaction.getSubCategory()));
        }
        return financeService.createTransaction(transaction);
    }
}

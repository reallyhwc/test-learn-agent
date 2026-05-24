package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.service.FinanceService;
import com.example.finance.util.LogMaskUtils;
import com.example.finance.util.XssUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final FinanceService financeService;

    public AccountController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Account> listAccounts(
            @RequestParam(required = false, defaultValue = "default") String userId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        if (limit == null || limit < 1) limit = 50;
        if (limit > 50) limit = 50;
        log.info("GET /api/accounts userId={} limit={}", LogMaskUtils.maskUserId(userId), limit);
        List<Account> accounts = financeService.listAccounts(userId);
        return accounts.size() > limit ? accounts.subList(0, limit) : accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account createAccount(@RequestBody Account account) {
        log.info("POST /api/accounts name={} type={} userId={}", account.getName(), account.getType(), LogMaskUtils.maskUserId(account.getUserId()));
        // XSS 清洗 name 字段
        if (account.getName() != null) {
            account.setName(XssUtils.sanitize(account.getName()));
        }
        return financeService.createAccount(account);
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        log.info("GET /api/accounts/{}/balance", id);
        return financeService.getBalance(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

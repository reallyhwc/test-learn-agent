package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
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
    public List<Account> listAccounts(@RequestParam(required = false, defaultValue = "default") String userId) {
        log.info("GET /api/accounts userId={}", userId);
        return financeService.listAccounts(userId);
    }

    @PostMapping
    public Account createAccount(@RequestBody Account account) {
        log.info("POST /api/accounts name={} type={} userId={}", account.getName(), account.getType(), account.getUserId());
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

package com.example.finance.controller;

import com.example.finance.model.Account;
import com.example.finance.service.FinanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final FinanceService financeService;

    public AccountController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Account> listAccounts(@RequestParam(required = false, defaultValue = "default") String userId) {
        return financeService.listAccounts(userId);
    }

    @PostMapping
    public Account createAccount(@RequestBody Account account) {
        return financeService.createAccount(account);
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        return financeService.getBalance(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

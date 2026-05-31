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

/**
 * 【账户管理 REST 控制器】
 *
 * <p>提供账户的查询和创建功能。所有接口按 userId 做多租户隔离。
 *
 * <ul>
 *   <li>{@code GET /api/accounts} — 查询用户账户列表（支持 limit 限制）</li>
 *   <li>{@code POST /api/accounts} — 创建新账户（name 字段 XSS 清洗后写入）</li>
 *   <li>{@code GET /api/accounts/{id}/balance} — 查询单个账户余额</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final FinanceService financeService;

    public AccountController(FinanceService financeService) {
        this.financeService = financeService;
    }

    /**
     * 查询指定用户的账户列表，最多返回 {@code limit} 条（上限 50）。
     *
     * @param userId 用户标识（默认 "default"）
     * @param limit 返回条数上限（默认 50）
     * @return 账户列表，按创建顺序返回
     */
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

    /**
     * 创建新账户。对 name 字段进行 XSS 清洗后写入存储。
     *
     * @param account 账户实体（含 name、type、userId）
     * @return 创建成功的账户（含自动生成的 id）
     */
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

    /**
     * 查询单个账户的当前余额。
     *
     * @param id 账户 ID
     * @return 200 + 余额（如账户存在），404（如账户不存在）
     */
    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        log.info("GET /api/accounts/{}/balance", id);
        return financeService.getBalance(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

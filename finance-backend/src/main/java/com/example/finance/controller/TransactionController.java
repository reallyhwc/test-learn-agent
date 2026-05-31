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

/**
 * 【交易流水 REST 控制器】
 *
 * <p>提供交易记录的查询、汇总和创建功能。支持多维度筛选（账户、日期范围、分类、交易类型）。
 *
 * <ul>
 *   <li>{@code GET /api/transactions} — 分页查询交易明细</li>
 *   <li>{@code GET /api/transactions/summary} — 按分类维度汇总交易金额</li>
 *   <li>{@code POST /api/transactions} — 创建交易记录（用户可控文本字段 XSS 清洗）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FinanceService financeService;

    public TransactionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    /**
     * 分页查询交易明细，支持按账户、日期、分类、交易类型等多条件组合筛选。
     *
     * @param userId 用户标识
     * @param accountId 账户 ID 筛选（可选）
     * @param startDate 起始日期（可选）
     * @param endDate 结束日期（可选）
     * @param category 一级分类筛选（可选）
     * @param subCategory 二级分类筛选（可选）
     * @param type 交易类型（INCOME/EXPENSE，可选）
     * @param page 页码（默认 1）
     * @param pageSize 每页条数（默认 20，上限 1000）
     * @return 分页结果（含 items、total、page、totalPages）
     */
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

    /**
     * 按分类维度汇总交易金额，支持按 {@code category} 或 {@code subCategory} 分组。
     *
     * @param userId 用户标识
     * @param type 交易类型筛选（可选）
     * @param startDate 起始日期（可选）
     * @param endDate 结束日期（可选）
     * @param groupBy 分组维度（"category" 或 "subCategory"，默认 "category"）
     * @return 汇总结果列表，每项包含分类名和金额
     */
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

    /**
     * 创建交易记录。对用户可控文本字段（note、category、subCategory）进行 XSS 清洗后写入。
     *
     * @param transaction 交易实体（含 amount、type、category 等）
     * @return 创建成功的交易记录（含自动生成的 id）
     */
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

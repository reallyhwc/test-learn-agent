package com.example.mcp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交易流水响应DTO，由MCP Server返回给AI Agent，描述一笔交易记录。
 */
@Data
@NoArgsConstructor
public class TransactionResponse {

    /** 流水唯一标识 */
    private Long id;

    /** 关联的账户ID */
    private Long accountId;

    /** 交易类型：INCOME(收入) / EXPENSE(支出) */
    private String type;

    /** 交易金额（正数，单位：元） */
    private BigDecimal amount;

    /** 一级分类，如"餐饮"、"工资"、"交通" */
    private String category;

    /** 二级分类，如"外卖"、"基本工资"、"打车" */
    private String subCategory;

    /** 备注说明 */
    private String note;

    /** 交易日期 */
    private LocalDate date;
}

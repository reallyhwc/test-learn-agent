package com.example.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 交易流水实体，记录每一笔收入或支出。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    /** 流水唯一标识 */
    private Long id;

    /** 关联的账户ID */
    private Long accountId;

    /** 交易类型：收入/支出 */
    private TransactionType type;

    /** 交易金额（正数，单位：元） */
    private BigDecimal amount;

    /** 交易分类，如"餐饮"、"工资"、"交通" */
    private String category;

    /** 备注说明 */
    private String note;

    /** 交易日期 */
    private LocalDate date;

    /** 所属用户ID */
    private String userId;
}

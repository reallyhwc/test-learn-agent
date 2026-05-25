package com.example.finance.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 交易类型枚举，区分收入与支出。
 */
@Getter
@AllArgsConstructor
public enum TransactionType {

    INCOME("收入"),
    EXPENSE("支出");

    /** 类型中文描述 */
    private final String description;
}

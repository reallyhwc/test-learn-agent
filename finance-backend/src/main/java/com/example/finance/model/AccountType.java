package com.example.finance.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 账户类型枚举，定义系统支持的资金账户种类。
 */
@Getter
@AllArgsConstructor
public enum AccountType {

    CASH("现金"),
    BANK("银行卡"),
    CARD("信用卡/第三方支付");

    /** 类型中文描述 */
    private final String description;
}

package com.example.finance.model;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 账户实体，表示用户的一个资金账户（如银行卡、现金、第三方支付等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    /** 账户唯一标识 */
    private Long id;

    /** 账户名称，如"招商银行储蓄卡"、"支付宝" */
    private String name;

    /** 账户类型 */
    private AccountType type;

    /** 账户余额（单位：元） */
    private BigDecimal balance;

    /** 所属用户ID */
    private String userId;
}

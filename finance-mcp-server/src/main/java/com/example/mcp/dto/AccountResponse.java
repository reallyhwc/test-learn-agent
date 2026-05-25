package com.example.mcp.dto;

import java.math.BigDecimal;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 账户响应DTO，由MCP Server返回给AI Agent，描述一个资金账户。
 */
@Data
@NoArgsConstructor
public class AccountResponse {

    /** 账户唯一标识 */
    private Long id;

    /** 账户名称，如"招商银行储蓄卡"、"支付宝" */
    private String name;

    /** 账户类型：CASH(现金) / BANK(银行卡) / CARD(信用卡/第三方) */
    private String type;

    /** 账户余额（单位：元） */
    private BigDecimal balance;
}

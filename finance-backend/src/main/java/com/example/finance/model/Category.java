package com.example.finance.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 交易分类实体，定义收入或支出的分类标签（如"餐饮"、"工资"等）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    /** 分类唯一标识 */
    private Long id;

    /** 分类名称 */
    private String name;

    /** 所属交易类型：收入分类或支出分类 */
    private TransactionType type;
}

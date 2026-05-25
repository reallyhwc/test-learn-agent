package com.example.finance.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 交易分类实体，支持二级树形结构（如"餐饮→外卖"）。
 * parentId 为 null 表示一级分类，非 null 表示二级分类。
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

    /** 父分类ID，null 表示一级分类 */
    private Long parentId;
}

package com.example.finance.dto;

import java.util.List;
import lombok.Getter;

/**
 * 通用分页结果包装类，用于封装列表查询的分页响应。
 *
 * @param <T> 列表元素类型
 */
@Getter
public class PageResult<T> {

    /** 当前页数据列表 */
    private final List<T> items;

    /** 当前页码（从1开始） */
    private final int page;

    /** 每页条数 */
    private final int pageSize;

    /** 总记录数 */
    private final long total;

    /** 总页数（自动计算） */
    private final int totalPages;

    public PageResult(List<T> items, int page, int pageSize, long total) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
    }
}

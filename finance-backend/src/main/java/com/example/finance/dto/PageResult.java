package com.example.finance.dto;

import java.util.List;

public class PageResult<T> {
    private List<T> items;
    private int page;
    private int pageSize;
    private long total;
    private int totalPages;

    public PageResult(List<T> items, int page, int pageSize, long total) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
    }

    public List<T> getItems() { return items; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public long getTotal() { return total; }
    public int getTotalPages() { return totalPages; }
}

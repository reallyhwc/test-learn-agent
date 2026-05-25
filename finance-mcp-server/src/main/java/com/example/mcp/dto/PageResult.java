package com.example.mcp.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用分页结果包装类，用于封装MCP接口的分页响应数据。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
public class PageResult<T> {

    /** 当前页数据列表 */
    private List<T> items;

    /** 当前页码（从1开始） */
    private int page;

    /** 每页条数 */
    private int pageSize;

    /** 总记录数 */
    private long total;

    /** 总页数 */
    private int totalPages;
}

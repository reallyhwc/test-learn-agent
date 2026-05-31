package com.example.finance.controller;

import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 【分类查询 REST 控制器】
 *
 * <p>返回树形分类结构：每个一级分类下挂 {@code children} 二级分类数组。
 * 分类数据在 Backend 启动时从 {@code data/categories.csv} 加载，运行期不变。
 */
@Slf4j
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final FinanceService financeService;

    public CategoryController(FinanceService financeService) {
        this.financeService = financeService;
    }

    /**
     * 返回树形分类列表，每个一级分类包含 children 二级分类数组。
     */
    @GetMapping
    public List<Map<String, Object>> listCategories() {
        log.info("GET /api/categories");
        return financeService.listCategoriesTree();
    }
}

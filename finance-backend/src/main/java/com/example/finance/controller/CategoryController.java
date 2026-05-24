package com.example.finance.controller;

import com.example.finance.model.Category;
import com.example.finance.service.FinanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final FinanceService financeService;

    public CategoryController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public List<Category> listCategories() {
        log.info("GET /api/categories");
        return financeService.listCategories();
    }
}

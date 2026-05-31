package com.example.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 【财务后端 Spring Boot 启动入口】
 *
 * <p>提供账户管理、交易流水、分类查询三大类 REST API。
 * 数据存储在 CSV 文件中（{@code finance-backend/data/}），零数据库依赖，启动即用。
 *
 * <p>模块在系统架构中的位置：
 * <pre>
 *   Frontend → Agent → MCP Server → Backend (this) → CSV
 * </pre>
 *
 * @see com.example.finance.controller.AccountController 账户 CRUD
 * @see com.example.finance.controller.TransactionController 交易流水
 * @see com.example.finance.controller.CategoryController 分类查询
 */
@SpringBootApplication
public class FinanceBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceBackendApplication.class, args);
    }
}

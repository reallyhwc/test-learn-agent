package com.example.finance.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【OpenAPI 文档配置】
 *
 * <p>通过 SpringDoc 自动生成 Swagger UI 接口文档。
 * 前端通过 {@code /swagger-ui.html} 访问交互式 API 文档。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建 OpenAPI 文档信息（标题、描述、版本）。
     *
     * @return OpenAPI 元数据对象
     */
    @Bean
    public OpenAPI financeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Personal Finance API")
                        .description("个人财务管理后端 API 文档")
                        .version("1.0.0"));
    }
}

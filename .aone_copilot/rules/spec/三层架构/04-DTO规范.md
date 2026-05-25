---
alwaysApply: true
---

# 04 DTO 与 Model 规范

## Model 类（实体）

- 注解：`@Data` + `@NoArgsConstructor` + `@AllArgsConstructor`
- 每个字段必须有 `/** Javadoc */` 注释
- 字段新增时注意：@AllArgsConstructor 会改变构造器签名，需同步修复所有调用方

## DTO 类（跨模块传输）

- 注解：`@Data` + `@NoArgsConstructor`
- 不加 `@AllArgsConstructor`（避免字段增减破坏调用方）
- 构造使用 setter 方式

## Response DTO

- 注解：`@Data` + `@NoArgsConstructor`
- 用于 MCP Server 返回给 Agent 的数据结构

## 枚举类

- 注解：`@Getter` + `@AllArgsConstructor`
- 值用全大写：`INCOME`、`EXPENSE`、`CASH`、`BANK`

## 通用规则

- 所有公共类和公共方法必须有 Javadoc
- 禁止在 DTO 中放业务逻辑
- 金额类型统一使用 `BigDecimal`
- 日期类型统一使用 `LocalDate`

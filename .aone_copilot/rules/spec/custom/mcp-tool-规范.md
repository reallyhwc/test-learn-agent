---
alwaysApply: true
---

# MCP 工具开发规范

## 注解规范

- `@McpTool(name, description)`
  - name：snake_case，如 `list_transactions`、`add_transaction`
  - description：详细说明工具用途、适用场景、与其他工具的区别

- `@McpToolParam(description)`
  - description 说明类型、是否必填、示例值

## 参数设计

- `userId`：必填，第一个参数，使用 `validateUserId()` 校验
- 复杂过滤条件：统一用 `filters` JSON 字符串传入，内部用 `parseFilters()` 解析
- filters 示例：`{"type":"INCOME","category":"餐饮","startDate":"2026-05-01"}`

## 错误处理

- 捕获所有异常，返回友好字符串（如 "查询余额失败，请检查账户ID是否正确"）
- 禁止抛异常（会中断 MCP 协议）
- userId 校验：在 try 块内调用 `validateUserId()`

## 指标埋点

- 成功：`recordSuccess("tool_name", startNs)`
- 失败：`recordError("tool_name", exception)`
- 每个工具方法开头记录 `long start = System.nanoTime()`

## URI 构建

- 中文参数必须使用 `UriComponentsBuilder.build().toUri()` 防二次编码
- 禁止手动拼接 URL 字符串

## 方法模板

```java
@McpTool(name = "tool_name", description = "工具用途描述")
public Object toolName(
        @McpToolParam(description = "用户ID") String userId,
        @McpToolParam(description = "参数描述") Type param) {
    long start = System.nanoTime();
    try {
        userId = validateUserId(userId);
        // 调用 Backend API
        recordSuccess("tool_name", start);
        return result;
    } catch (Exception e) {
        recordError("tool_name", e);
        return "操作失败，请稍后重试";
    }
}
```

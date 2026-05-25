---
name: add-mcp-tool
description: 当需要新增一个 MCP 工具（如 add_account、delete_transaction 等）时使用此 skill
---

# 新增 MCP 工具

## 检查清单

1. [ ] **Backend API**: 在对应 Controller 中新增 REST 端点
2. [ ] **Backend Service**: 实现业务逻辑
3. [ ] **Backend Test**: 新增 Controller 测试用例
4. [ ] **MCP FinanceTools**: 新增 @McpTool 方法
   - name 用 snake_case
   - description 详细说明用途和适用场景
   - 参数用 @McpToolParam 标注
   - userId 必填并校验
   - try-catch 返回友好消息
   - recordSuccess/recordError 埋点
5. [ ] **MCP DTO**: 如需新返回类型，创建 Response DTO
6. [ ] **Agent Prompt**: 在 buildSystemPrompt 中更新工具列表和决策规则
7. [ ] **编译验证**: 分别编译 backend、mcp-server、agent

## 方法模板

```java
@McpTool(name = "tool_name", description = "工具用途")
public Object toolName(
        @McpToolParam(description = "用户ID") String userId,
        @McpToolParam(description = "参数") Type param) {
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

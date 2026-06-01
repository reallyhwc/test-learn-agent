# AI Coding Harness 工程脚手架建设

为 Personal Finance Agent 项目建立完善的 AI Coding Harness 体系，使 AI 编程工具（Copilot/Claude Code/Aone Copilot）在操作本项目时有明确的规范约束、操作指南和防御规则。

## Proposed Changes

### 方向一：Rules 编码规范体系

#### [NEW] [工程结构.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/工程结构.md)
- 项目目录树（带中文注释）
- 4 个模块的职责、端口、关键类
- 技术栈清单
- 跨模块变更影响链

#### [NEW] [01-命名规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/01-命名规范.md)
- 类名/方法名/包名/变量名规则
- 前后端命名差异

#### [NEW] [02-分层规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/02-分层规范.md)
- Controller→Service→Repository 的职责边界
- 禁止跨层调用规则

#### [NEW] [03-异常处理规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/03-异常处理规范.md)
- 全局异常 vs 业务异常
- GlobalExceptionHandler 覆盖范围

#### [NEW] [04-DTO规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/04-DTO规范.md)
- Lombok 注解选择规则
- Javadoc 要求

#### [NEW] [05-测试规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/05-测试规范.md)
- 测试命名、状态码约定
- 测试数据管理

#### [NEW] [06-前端规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/三层架构/06-前端规范.md)
- Vue 组件结构、API 调用、状态管理

#### [NEW] [mcp-tool-规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/custom/mcp-tool-规范.md)
- @McpTool 注解规范、参数设计

#### [NEW] [csv-存储规范.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/rules/spec/custom/csv-存储规范.md)
- CSV Schema 变更流程、兼容性规则

---

### 方向二：强化 CLAUDE.md

#### [MODIFY] [CLAUDE.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/CLAUDE.md)
- 新增 Testing Strategy 章节
- 新增 AI Coding Constraints 章节
- 新增 Module Dependency Order 章节
- 新增 Known Tech Debt 章节
- 新增 Anti-Patterns 章节

---

### 方向三：项目级 Skills

#### [NEW] [add-model-field/SKILL.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/skills/add-model-field/SKILL.md)
- "给 Model 加字段"的标准操作清单（11 步）

#### [NEW] [add-mcp-tool/SKILL.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/skills/add-mcp-tool/SKILL.md)
- "新增 MCP 工具"的标准操作清单

#### [NEW] [csv-migration/SKILL.md](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/.aone_copilot/skills/csv-migration/SKILL.md)
- "CSV Schema 升级"的兼容性操作清单

---

### 方向四：防御性设定

防御性规则将内嵌到 CLAUDE.md 的 Anti-Patterns 章节和各 Rules 文件中，不单独建文件。

---

## Verification Plan

### Automated Tests
- 所有新文件均为 Markdown 文档，无需编译验证
- 确认文件路径和目录结构正确

### Manual Verification
- 启动新 session，验证 AI 是否自动读取 rules 和 skills
- 尝试"给 Model 加字段"场景，验证 skill 是否被正确触发

---
生成时间: 2026/5/25 17:49:18
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e
plan_status: review
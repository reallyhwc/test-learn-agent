# 自动化测试体系深度完善 — 任务清单

## Phase 1: 测试规范规则完善

- [x] 1.1 扩展 `05-测试规范.md`：新增前端测试规范章节（Vitest + jsdom + Vue Test Utils）
- [x] 1.2 扩展 `05-测试规范.md`：新增 Python 测试规范章节（pytest + pytest-asyncio）
- [x] 1.3 扩展 `05-测试规范.md`：新增通用测试覆盖率要求和 CI 卡点规则
- [x] 1.4 Git commit Phase 1

## Phase 2: 前端测试补全

- [x] 2.1 补充 `streamParser.test.js`：CRLF 格式兼容测试用例（+6 用例）
- [x] 2.2 新建 `aiStore.test.js`：Agent/MCP 切换、localStorage 持久化、轮询等待（18 用例）
- [x] 2.3 新建 `AppHeader.test.js`：双 select 渲染、切换事件（4 用例）
- [x] 2.4 新建 `TransactionList.test.js`：列表渲染、筛选、分页（4 用例）
- [x] 2.5 新建 `AccountList.test.js`：账户列表、余额展示（5 用例）
- [x] 2.6 运行 `npx vitest run` 验证全部前端测试通过 ✅ 14 files, 109 tests
- [x] 2.7 Git commit Phase 2

## Phase 3: Java Agent 测试补全

- [x] 3.1 新建 `SimpleCircuitBreakerTest.java`：状态转换行为验证（8 用例）
- [x] 3.2 新建 `FeedbackControllerTest.java`：standaloneSetup 方式（3 用例）
- [x] 3.3 新建 `MemoryControllerTest.java`：standaloneSetup + Mockito mock（3 用例）
- [x] 3.4 运行 `./mvnw test -q` 验证新增 Java Agent 测试全通过 ✅
- [x] 3.5 Git commit Phase 3

## Phase 4: Python Agent 测试补全

- [x] 4.1 新建 `test_config_loader.py`：环境变量优先级、缺失 key 异常、YAML 替换（5 用例）
- [x] 4.2 新建 `test_memory_manager.py`：CRUD、滚动淘汰、持久化、多用户（8 用例）
- [x] 4.3 新建 `test_system_prompt.py`：prompt 构建、账户摘要格式化（10 用例）
- [x] 4.4 扩展 `test_chat_server.py`：memory_count/switch-mcp/sanitize 补充用例（+6 用例）
- [x] 4.5 运行 `python -m pytest tests/ -v` 验证全部 Python 测试通过 ✅ 33 passed
- [x] 4.6 Git commit Phase 4

---
生成时间: 2026/5/27 15:38:06
planId: 585e3811-d0b5-4693-8c2d-01341f775001
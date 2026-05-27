# Guardrails 三层防护 — 任务清单

## Phase 1: Java Agent 输入防护
- [ ] 新建 `PromptInjectionDetector.java`（正则检测工具类）
- [ ] 新建 `InputGuardrailAdvisor.java`（实现 `BaseAdvisor`，`before()` 注入检测）
- [ ] 新建 `InputGuardrailAdvisorTest.java`（注入检测测试）
- [ ] 修改 `ChatController.java` 注册 InputGuardrailAdvisor

## Phase 2: Java Agent 工具调用防护
- [ ] 新建 `ToolCallGuardrailAdvisor.java`（userId 覆盖 + 金额校验 + 频率限制）
- [ ] 新建 `ToolCallGuardrailAdvisorTest.java`
- [ ] 修改 `ChatController.java` 注册 ToolCallGuardrailAdvisor

## Phase 3: Java Agent 输出防护
- [ ] 新建 `OutputGuardrailAdvisor.java`（金额一致性检测）
- [ ] 新建 `OutputGuardrailAdvisorTest.java`
- [ ] 修改 `ChatController.java` 注册 OutputGuardrailAdvisor
- [ ] 运行 `./mvnw test` 验证 Java Agent 全量测试通过
- [ ] Git commit Phase 1-3

## Phase 4: Python Agent Guardrails
- [ ] 新建 `guardrails.py`（PromptInjectionDetector + InputGuardrail + ToolCallGuardrail + OutputGuardrail）
- [ ] 修改 `chat_server.py` 集成 InputGuardrail
- [ ] 修改 `agent.py` 集成 ToolCallGuardrail 回调
- [ ] 新建 `tests/test_guardrails.py`
- [ ] 运行 `pytest` 验证 Python 测试通过
- [ ] Git commit Phase 4

## Phase 5: 文档与收尾
- [ ] 更新 `docs/roadmap/01-guardrails.md` 状态标记
- [ ] Git commit Phase 5

---
生成时间: 2026/5/27 17:52:29
planId: 585e3811-d0b5-4693-8c2d-01341f775001
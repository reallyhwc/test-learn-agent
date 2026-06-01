# 2×2 自由组合改造任务清单

## Phase 1: 修复 Python Agent SSE 无回显

- [x] 1.1 修改 agent.py：ChatDeepSeek → ChatOpenAI，修复 LLM 兼容性
- [x] 1.2 修改 requirements.txt：langchain-deepseek → langchain-openai
- [x] 1.3 安装新依赖：pip install langchain-openai（venv 创建于 .venv/）
- [x] 1.4 Python 语法检查：py_compile agent.py + chat_server.py（全部 6 文件 OK）
- [x] 1.5 启动 Backend + Python MCP + Python Agent，测试 SSE 流式回显（已修复：base_url 需加 /v1）

## Phase 2: 前端 2×2 自由组合

- [x] 2.1 修改 aiStore.js：分离 agentType/mcpType + switchMcp() + localStorage + 就绪轮询
- [x] 2.2 修改 AppHeader.vue：Agent/MCP 双 el-select + loading 状态
- [x] 2.3 修改 ChatPanel.vue：watch mcpType + mcpSwitching 双 watcher 协作
- [x] 2.4 前端测试：npx vitest run（70/72 通过，2 个历史遗留失败）

## Phase 3: Python Agent 动态 MCP 切换

- [x] 3.1 修改 agent.py：_connect_mcp() 抽取 + close() 简化（运行时重连不可行，改为进程重启）
- [x] 3.2 修改 chat_server.py：POST /api/switch-mcp 进程级重启 + import 规范化 + venv fallback
- [x] 3.3 Python 语法检查（agent.py + chat_server.py + main.py OK）

## Phase 4: Java Agent MCP URL 外部化

- [x] 4.1 修改 application.yml：MCP URL 从环境变量 MCP_SSE_URL 读取
- [x] 4.2 新建 McpSwitchController.java：POST /api/switch-mcp + /api/config + 进程重启 + 项目根目录检测 + URL 格式修复
- [x] 4.3 Java 编译检查（JAVA_HOME=/opt/homebrew/opt/openjdk@17 编译通过）

## Phase 5: 启动脚本 + 配置

- [x] 5.1 修改 config.yaml：mode 改为 dual
- [x] 5.2 修改 start-all.sh：restart-java-agent 子命令 + MCP_SSE_URL export + nohup 日志输出 + JAVA_HOME fallback
- [x] 5.3 清理 vite.config.js：删除遗留的 getAgentPort() 和无用代理规则

## Phase 6: 端到端验证

- [x] 6.1 测试 Python Agent + Python MCP → SSE 正常 ✅
- [x] 6.2 测试 Python Agent + Java MCP（切换重启） → SSE 正常 ✅
- [x] 6.3 验证 MCP 双向切换（Python→Java→Python） → 全部正常 ✅
- [ ] 6.4 测试 Java Agent + Java/Python MCP（需用户启动 Java Agent）
- [ ] 6.5 验证前端 UI 双 select 切换 + localStorage（需用户打开浏览器）

---
生成时间: 2026/5/27 13:48:45
planId: 585e3811-d0b5-4693-8c2d-01341f775001
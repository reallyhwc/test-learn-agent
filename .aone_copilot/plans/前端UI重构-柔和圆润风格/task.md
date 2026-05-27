# 前端 UI 重构 — 任务清单

## Phase 1：主题基础设施

- [x] 创建 `src/assets/theme.css`：CSS 变量体系（亮色 + 暗色）+ Element Plus 变量覆盖
- [x] 修改 `main.js`：引入 theme.css，启动时加载主题偏好
- [x] 修改 `userStore.js`：新增 theme 状态 + toggleTheme + localStorage 持久化

## Phase 2：布局与导航

- [x] 修改 `App.vue`：根元素绑定 data-theme，优化布局间距
- [x] 修改 `AppHeader.vue`：白色/深灰 header + Logo 渐变 + 主题切换按钮 + 胶囊选择器

## Phase 3：业务组件样式

- [x] 修改 `AccountList.vue`：大圆角卡片 + 悬停上浮 + 胶囊标签 + CSS 变量颜色
- [x] 修改 `TransactionForm.vue`：圆角卡片包裹 + 圆角输入框 + 紫色渐变按钮
- [x] 修改 `TransactionList.vue`：圆角卡片 + 圆角表格行 + 筛选区统一样式
- [x] 修改 `ChartPanel.vue`：圆角卡片 + 图表配色改 CSS 变量 + 移动端单栏
- [x] 修改 `ChatPanel.vue`：大圆角面板 + 胶囊输入框 + 紫色发送按钮
- [x] 修改 `ChatMessage.vue`：紫色渐变用户气泡 + 暗色模式 Markdown 适配

## Phase 4：验证

- [x] 运行前端测试 `npx vitest run`
- [x] 提交代码

---
生成时间: 2026/5/25 19:22:38
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e
# 前端 UI 重构 — 柔和圆润风格 + 暗色模式

将前端从 Element Plus 默认主题重构为方案 C（Soft & Rounded）风格，新增暗色/亮色模式切换。

## Proposed Changes

### 全局主题层

#### [NEW] [theme.css](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/assets/theme.css)
- 定义 CSS 变量体系（亮色 `:root` + 暗色 `[data-theme="dark"]`）
- 覆盖 Element Plus 默认变量（`--el-color-primary` → 紫色 `#6366f1`）
- 统一圆角（卡片 `20px`、输入框 `12px`、按钮 `12px`、气泡 `18px`）
- 统一阴影、间距、字体
- 暗色模式颜色方案（背景 `#0f1117`、卡片 `#1a1b23`、边框 `#2a2b35`）

---

### 布局与导航

#### [MODIFY] [App.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/App.vue)
- 根元素绑定 `data-theme` 属性（响应 Pinia store 的主题状态）
- 优化主体布局间距（`padding: 20px`、`gap: 20px`）
- 移动端 Tab 栏样式适配新主题

#### [MODIFY] [AppHeader.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/AppHeader.vue)
- Header 背景改为白色（亮色）/ 深灰（暗色），去掉硬编码 `#1a1a2e`
- Logo 区域增加渐变图标
- 右侧新增 🌙/☀️ 主题切换按钮
- 用户选择器改为圆角胶囊样式

#### [MODIFY] [main.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/main.js)
- 引入 `theme.css`
- 启动时从 localStorage 读取主题偏好并应用

---

### 状态管理

#### [MODIFY] [userStore.js](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/stores/userStore.js)
- 新增 `theme` 状态（`'light'` / `'dark'`）
- 新增 `toggleTheme()` action
- localStorage 持久化主题偏好
- 初始化时检测系统偏好（`prefers-color-scheme`）

---

### 业务组件

#### [MODIFY] [AccountList.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/AccountList.vue)
- 卡片样式：大圆角 `16px`、悬停上浮 + 阴影增强
- 余额字体加大加粗，使用 `font-variant-numeric: tabular-nums`
- 标签改为胶囊样式（圆角 `20px`、浅紫背景）
- 颜色统一使用 CSS 变量

#### [MODIFY] [TransactionForm.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/TransactionForm.vue)
- 包裹在大圆角卡片中（`border-radius: 20px`）
- 输入框圆角 `12px`，focus 时紫色边框 + 浅紫光晕
- 提交按钮改为紫色渐变 + 阴影

#### [MODIFY] [TransactionList.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/TransactionList.vue)
- 列表包裹在大圆角卡片中
- 表格行增加圆角背景（`border-spacing: 0 8px`）
- 悬停效果改为浅灰/深灰背景
- 筛选区输入框统一圆角样式

#### [MODIFY] [ChartPanel.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/ChartPanel.vue)
- 卡片大圆角包裹
- 图表配色从硬编码改为 CSS 变量引用
- 移动端改为单栏堆叠（`:xs="24"`）

#### [MODIFY] [ChatPanel.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/ChatPanel.vue)
- 聊天面板增加大圆角（桌面端 `20px`）
- 输入框改为圆角胶囊 `20px`
- 发送按钮紫色渐变 + 阴影
- 背景色跟随主题变量

#### [MODIFY] [ChatMessage.vue](file:///Users/xuhu/workspace/xuhuLocal/test-learn-agent/finance-frontend/src/components/ChatMessage.vue)
- 用户气泡：紫色渐变背景
- AI 气泡：浅灰背景（亮色）/ 深灰背景（暗色）
- Markdown 表格/代码块样式适配暗色模式

---

## Verification Plan

### Automated Tests
```bash
cd finance-frontend && npx vitest run
```

### Manual Verification
- 启动前端 `npm run dev`，检查亮色模式下所有组件样式
- 切换暗色模式，检查所有组件是否正确跟随
- 刷新页面，验证主题偏好持久化
- 移动端视口（Chrome DevTools）验证响应式

---
生成时间: 2026/5/25 19:22:38
planId: 0f5975b3-7d64-421a-979a-273fcd0ec38e
plan_status: review
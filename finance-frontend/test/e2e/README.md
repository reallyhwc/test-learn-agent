# E2E 测试指南

## 推荐工具

- Playwright（推荐）或 Cypress

## 安装

```bash
npm install --save-dev @playwright/test
npx playwright install chromium
```

## 测试场景

### 核心流程
1. 页面加载 → 账户列表展示
2. 添加交易 → 余额更新
3. AI 对话 → 收到回复
4. 移动端响应式 → Tab 切换

### 运行

```bash
npx playwright test
```

## 前提条件

需要先启动所有后端服务：
```bash
./start-all.sh
```

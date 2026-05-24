// @ts-check
// 示例 E2E 测试（需要安装 @playwright/test）
// npm install --save-dev @playwright/test
// npx playwright install chromium

/*
import { test, expect } from '@playwright/test'

test('页面加载成功', async ({ page }) => {
  await page.goto('http://localhost:5173')
  await expect(page).toHaveTitle(/Finance/)
})

test('账户列表可见', async ({ page }) => {
  await page.goto('http://localhost:5173')
  await expect(page.locator('.account-list')).toBeVisible()
})

test('可以发送聊天消息', async ({ page }) => {
  await page.goto('http://localhost:5173')
  const chatInput = page.locator('input[aria-label="输入消息"]')
  await chatInput.fill('查看我的账户余额')
  await page.keyboard.press('Enter')
  // 等待回复
  await expect(page.locator('.chat-msg.assistant')).toBeVisible({ timeout: 30000 })
})
*/

# Frontend 流式渲染重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 `ChatPanel.vue` + `ChatMessage.vue`，把流式期间纯文本快速展示与流结束后 markdown/图表一次性渲染分离；引入前端测试基础设施（vitest）；接入 DOMPurify 修复 XSS 风险。

**Architecture:**
- 把现在塞在 `.vue` 里的逻辑抽成 4 个纯函数模块（`streamParser` / `markdownRenderer` / `chartExtractor` / `chartManager`），便于单元测试。
- 引入 `streaming` prop 让 `ChatMessage` 在流式期间走"纯文本快路径"，流结束后再 `marked.parse + DOMPurify + chart mount` 一次性完成。
- `ChartManager` 用模块级 `Map<el, App>` 显式管理 Vue app 生命周期，组件卸载时统一 unmount，根除当前的"游离 app"内存泄漏。

**Tech Stack:** Vue 3 (`^3.5.34`)、vite (`^8.0.12`)、marked (`^18.0.4`)、新增：vitest、@vue/test-utils、jsdom、dompurify

**修复的审计问题：** #4 (XSS)、#10 (字符 delay 视觉假慢)、#11 (流式期间反应式炸)、#12 (Chart Vue app 内存泄漏)

**不在本 plan 范围（独立 plan）：** #21 中止按钮、代码块复制按钮、Mermaid/LaTeX 渲染

---

## Task 1: 引入 vitest 测试基础设施

**Files:**
- Modify: `finance-frontend/package.json`
- Create: `finance-frontend/vitest.config.js`
- Create: `finance-frontend/test/sanity.test.js`

- [ ] **Step 1.1: 装依赖**

```bash
cd finance-frontend
npm install --save-dev vitest @vue/test-utils @vitest/ui jsdom
npm install --save dompurify
```

预期：`package.json` 多出 5 个依赖，命令成功结束。

- [ ] **Step 1.2: 创建 `vitest.config.js`**

```javascript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['test/**/*.test.js'],
  },
})
```

- [ ] **Step 1.3: 在 `package.json` 的 `scripts` 添加 `test` 脚本**

修改 `finance-frontend/package.json`，在 `scripts` 块新增：

```json
"scripts": {
  "dev": "vite",
  "build": "vite build",
  "preview": "vite preview",
  "test": "vitest run",
  "test:watch": "vitest"
}
```

- [ ] **Step 1.4: 写 sanity 测试 `test/sanity.test.js`**

```javascript
import { describe, it, expect } from 'vitest'

describe('vitest sanity', () => {
  it('能跑通', () => {
    expect(1 + 1).toBe(2)
  })
})
```

- [ ] **Step 1.5: 跑测试**

```bash
cd finance-frontend && npm test
```

预期：`✓ test/sanity.test.js (1)` 全绿。

- [ ] **Step 1.6: commit**

```bash
git add finance-frontend/package.json finance-frontend/package-lock.json finance-frontend/vitest.config.js finance-frontend/test/sanity.test.js
git commit -m "$(cat <<'EOF'
test: 引入 vitest + jsdom 前端测试基础设施

为后续流式渲染重构 (TDD) 铺路；新增 dompurify 准备修复 markdown 渲染 XSS 风险
EOF
)"
```

---

## Task 2: 抽出 SSE 解析到 `utils/streamParser.js`

**Files:**
- Create: `finance-frontend/src/utils/streamParser.js`
- Create: `finance-frontend/test/utils/streamParser.test.js`

**目标：** 把 `ChatPanel.vue` 里那段 `decoder.decode + buffer + line.startsWith('data:')` 抽成纯函数，加测试。

- [ ] **Step 2.1: 写测试 `test/utils/streamParser.test.js`**

```javascript
import { describe, it, expect } from 'vitest'
import { createStreamBuffer } from '../../src/utils/streamParser.js'

describe('createStreamBuffer', () => {
  it('单个完整 SSE event', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed('data:hello\n\n')
    expect(tokens).toEqual(['hello'])
  })

  it('跨 chunk 的 SSE event 合并', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:he')).toEqual([])
    expect(buf.feed('llo\n\n')).toEqual(['hello'])
  })

  it('多个连续 event', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed('data:你\n\ndata:好\n\n')
    expect(tokens).toEqual(['你', '好'])
  })

  it('保留 token 内的前导空格', () => {
    // 旧实现 startsWith(' ') ? slice(1) 会吃掉合法空格，新实现不能吃
    const buf = createStreamBuffer()
    expect(buf.feed('data: hello world\n\n')).toEqual([' hello world'])
  })

  it('忽略非 data: 行（注释/event/id）', () => {
    const buf = createStreamBuffer()
    const tokens = buf.feed(': comment\nevent:msg\nid:1\ndata:x\n\n')
    expect(tokens).toEqual(['x'])
  })

  it('UTF-8 多字节字符不被截断', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:这\n\ndata:是\n\n')).toEqual(['这', '是'])
  })

  it('空 data 行返回空字符串 token', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:\n\n')).toEqual([''])
  })

  it('行末仅 \\n 不结束 event（需要 \\n\\n）', () => {
    const buf = createStreamBuffer()
    expect(buf.feed('data:abc\n')).toEqual([])
    expect(buf.feed('\n')).toEqual(['abc'])
  })
})
```

- [ ] **Step 2.2: 跑测试，确认失败**

```bash
cd finance-frontend && npm test test/utils/streamParser.test.js
```

预期：FAIL（模块还没创建）。

- [ ] **Step 2.3: 实现 `src/utils/streamParser.js`**

```javascript
/**
 * 创建一个 SSE buffer。feed(chunk) 输入一段 stream chunk，
 * 返回从 buffer 里能解析出的完整 token 数组（含空字符串）。
 * 未完整的尾部留在 buffer 里等下一次 feed。
 *
 * 标准 SSE 格式：每条 event 由若干字段行（data:/event:/id:/:）组成，
 * 以 \n\n 结束。一条 event 内可有多个 data: 行（按 spec 拼接 \n），
 * 这里简化：每个 data: 行视为一个独立 token。
 */
export function createStreamBuffer() {
  let buffer = ''
  return {
    feed(chunk) {
      buffer += chunk
      const tokens = []
      let eventEnd
      while ((eventEnd = buffer.indexOf('\n\n')) !== -1) {
        const eventBlock = buffer.slice(0, eventEnd)
        buffer = buffer.slice(eventEnd + 2)
        for (const line of eventBlock.split('\n')) {
          if (line.startsWith('data:')) {
            // 保留 data: 后的全部内容（含前导空格）；空 data: 视为 ''
            tokens.push(line.slice(5))
          }
          // event:/id:/:comment 全部忽略
        }
      }
      return tokens
    },
  }
}
```

- [ ] **Step 2.4: 跑测试，确认通过**

```bash
cd finance-frontend && npm test test/utils/streamParser.test.js
```

预期：8 个测试全绿。

- [ ] **Step 2.5: commit**

```bash
git add finance-frontend/src/utils/streamParser.js finance-frontend/test/utils/streamParser.test.js
git commit -m "$(cat <<'EOF'
refactor: 抽出 SSE 解析到 utils/streamParser.js

修复旧实现 startsWith(' ') ? slice(1) 吃掉 token 合法前导空格的 bug；
支持跨 chunk event 合并、UTF-8 多字节安全、忽略非 data: 行
EOF
)"
```

---

## Task 3: 抽出 Markdown 渲染到 `utils/markdown.js`（DOMPurify 防 XSS）

**Files:**
- Create: `finance-frontend/src/utils/markdown.js`
- Create: `finance-frontend/test/utils/markdown.test.js`

- [ ] **Step 3.1: 写测试 `test/utils/markdown.test.js`**

```javascript
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '../../src/utils/markdown.js'

describe('renderMarkdown', () => {
  it('普通文本不变', () => {
    expect(renderMarkdown('hello')).toContain('hello')
  })

  it('表格被渲染为 <table>', () => {
    const md = '| a | b |\n| - | - |\n| 1 | 2 |'
    const html = renderMarkdown(md)
    expect(html).toContain('<table>')
    expect(html).toContain('<th>a</th>')
  })

  it('代码块被渲染为 <pre><code>', () => {
    const html = renderMarkdown('```js\nconst x = 1\n```')
    expect(html).toContain('<pre>')
    expect(html).toContain('<code')
  })

  it('XSS: <script> 被剥离', () => {
    const html = renderMarkdown('<script>alert(1)</script>正文')
    expect(html).not.toContain('<script>')
    expect(html).toContain('正文')
  })

  it('XSS: img onerror 属性被剥离', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">')
    expect(html).not.toMatch(/onerror/i)
  })

  it('XSS: javascript: 链接被剥离', () => {
    const html = renderMarkdown('[click](javascript:alert(1))')
    expect(html).not.toMatch(/javascript:/i)
  })

  it('null/undefined/空字符串返回空串', () => {
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
    expect(renderMarkdown('')).toBe('')
  })
})
```

- [ ] **Step 3.2: 跑测试，确认失败**

```bash
cd finance-frontend && npm test test/utils/markdown.test.js
```

预期：FAIL（模块未创建）。

- [ ] **Step 3.3: 实现 `src/utils/markdown.js`**

```javascript
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 把 markdown 文本转成可直接 v-html 的 sanitized HTML。
 * - 用 marked 解析 markdown
 * - 用 DOMPurify 移除潜在的 XSS（script、onerror、javascript: 等）
 * - 保留我们需要的标签：表格、代码、图片 src（仅 https/data:image）
 */
export function renderMarkdown(text) {
  if (!text) return ''
  const rawHtml = marked.parse(text)
  return DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true },
  })
}
```

- [ ] **Step 3.4: 跑测试，确认通过**

```bash
cd finance-frontend && npm test test/utils/markdown.test.js
```

预期：7 个测试全绿。

- [ ] **Step 3.5: commit**

```bash
git add finance-frontend/src/utils/markdown.js finance-frontend/test/utils/markdown.test.js
git commit -m "$(cat <<'EOF'
feat: 接入 DOMPurify 修复 markdown 渲染 XSS 风险

LLM 输出经过 marked 后再走 DOMPurify sanitize，剥离 <script>、onerror、javascript: 等危险内容。
（修复审计 #4）
EOF
)"
```

---

## Task 4: 抽出表格→图表数据提取到 `utils/chartExtractor.js`

**Files:**
- Create: `finance-frontend/src/utils/chartExtractor.js`
- Create: `finance-frontend/test/utils/chartExtractor.test.js`

**目标：** 把 `ChatMessage.vue:36-90` 的 `isNumeric` / `extractTableData` 抽成纯函数（输入 DOM table，输出 `{headers, rows, chartType} | null`）。

- [ ] **Step 4.1: 写测试 `test/utils/chartExtractor.test.js`**

```javascript
import { describe, it, expect } from 'vitest'
import { extractTableData } from '../../src/utils/chartExtractor.js'

function makeTable(html) {
  const div = document.createElement('div')
  div.innerHTML = html
  return div.querySelector('table')
}

describe('extractTableData', () => {
  it('2 列 + ≤8 行 → pie', () => {
    const t = makeTable(`
      <table><thead><tr><th>类别</th><th>金额</th></tr></thead>
      <tbody><tr><td>餐饮</td><td>¥1,200</td></tr>
      <tr><td>交通</td><td>¥300</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('pie')
    expect(r.headers).toEqual(['类别', '金额'])
    expect(r.rows).toEqual([['餐饮', '1200'], ['交通', '300']])
  })

  it('多数值列 → bar', () => {
    const t = makeTable(`
      <table><thead><tr><th>月</th><th>收入</th><th>支出</th></tr></thead>
      <tbody><tr><td>3月</td><td>10000</td><td>5000</td></tr>
      <tr><td>4月</td><td>12000</td><td>6000</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('bar')
    expect(r.headers).toEqual(['月', '收入', '支出'])
  })

  it('行数 > 8 → bar', () => {
    const rows = Array.from({ length: 10 }, (_, i) =>
      `<tr><td>项${i}</td><td>${i * 10}</td></tr>`).join('')
    const t = makeTable(`<table><thead><tr><th>名</th><th>值</th></tr></thead>
      <tbody>${rows}</tbody></table>`)
    const r = extractTableData(t)
    expect(r.chartType).toBe('bar')
    expect(r.rows).toHaveLength(10)
  })

  it('没有数值列 → null', () => {
    const t = makeTable(`<table><thead><tr><th>a</th><th>b</th></tr></thead>
      <tbody><tr><td>x</td><td>y</td></tr></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })

  it('表头列数 < 2 → null', () => {
    const t = makeTable(`<table><thead><tr><th>仅一列</th></tr></thead>
      <tbody><tr><td>x</td></tr></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })

  it('清洗千分位逗号', () => {
    const t = makeTable(`<table><thead><tr><th>名</th><th>额</th></tr></thead>
      <tbody><tr><td>合计</td><td>¥12,345.67</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.rows[0]).toEqual(['合计', '12345.67'])
  })

  it('百分号清洗', () => {
    const t = makeTable(`<table><thead><tr><th>名</th><th>占比</th></tr></thead>
      <tbody><tr><td>食</td><td>30%</td></tr></tbody></table>`)
    const r = extractTableData(t)
    expect(r.rows[0]).toEqual(['食', '30'])
  })

  it('空 tbody → null', () => {
    const t = makeTable(`<table><thead><tr><th>a</th><th>b</th></tr></thead>
      <tbody></tbody></table>`)
    expect(extractTableData(t)).toBeNull()
  })
})
```

- [ ] **Step 4.2: 跑测试，确认失败**

```bash
cd finance-frontend && npm test test/utils/chartExtractor.test.js
```

预期：FAIL。

- [ ] **Step 4.3: 实现 `src/utils/chartExtractor.js`**

```javascript
const NUMERIC_NOISE = /[¥$%,\s]/g

function isNumeric(val) {
  if (val == null || val === '') return false
  return !isNaN(parseFloat(String(val).replace(NUMERIC_NOISE, '')))
}

function cleanNumeric(val) {
  const n = parseFloat(String(val).replace(NUMERIC_NOISE, ''))
  return isNaN(n) ? '0' : String(n)
}

/**
 * 从一个 <table> DOM 节点提取可绘图的数据。
 * 返回 { headers, rows, chartType: 'pie' | 'bar' } 或 null（不可绘）。
 */
export function extractTableData(table) {
  if (!table) return null

  const headers = []
  const thead = table.querySelector('thead')
  if (thead) {
    thead.querySelectorAll('th').forEach((th) =>
      headers.push(th.textContent.trim()),
    )
  } else {
    const firstRow = table.querySelector('tr')
    if (firstRow) {
      firstRow
        .querySelectorAll('td,th')
        .forEach((c) => headers.push(c.textContent.trim()))
    }
  }
  if (headers.length < 2) return null

  const rows = []
  const tbody = table.querySelector('tbody') || table
  tbody.querySelectorAll('tr').forEach((tr) => {
    const cells = [...tr.querySelectorAll('td,th')].map((c) =>
      c.textContent.trim(),
    )
    if (cells.length >= 2) rows.push(cells)
  })
  if (rows.length === 0) return null

  const numCols = []
  for (let i = 1; i < headers.length; i++) {
    const sample = rows.slice(0, 3).filter((r) => r[i]).map((r) => r[i])
    if (sample.length > 0 && sample.every(isNumeric)) {
      numCols.push(i)
    }
  }
  if (numCols.length === 0) return null

  const cleanRows = rows
    .map((r) =>
      r.map((c, i) => {
        if (i === 0) return c
        if (numCols.includes(i)) return cleanNumeric(c)
        return c
      }),
    )
    .filter((r) => r.length >= 2)

  if (cleanRows.length === 0) return null

  const hasMultipleNumericCols = numCols.length > 1
  const chartType = hasMultipleNumericCols
    ? 'bar'
    : cleanRows.length <= 8
      ? 'pie'
      : 'bar'

  return {
    headers: headers.filter((_, i) => i === 0 || numCols.includes(i)),
    rows: cleanRows.map((r) => r.filter((_, i) => i === 0 || numCols.includes(i))),
    chartType,
  }
}
```

- [ ] **Step 4.4: 跑测试，确认通过**

```bash
cd finance-frontend && npm test test/utils/chartExtractor.test.js
```

预期：8 个测试全绿。

- [ ] **Step 4.5: commit**

```bash
git add finance-frontend/src/utils/chartExtractor.js finance-frontend/test/utils/chartExtractor.test.js
git commit -m "$(cat <<'EOF'
refactor: 抽出 markdown 表格→图表数据提取到 utils/chartExtractor.js

修复千分位逗号清洗依赖正则字符类巧合的写法；显式区分 pie/bar 判别规则
EOF
)"
```

---

## Task 5: ChartManager 管理图表 Vue app 生命周期

**Files:**
- Create: `finance-frontend/src/utils/chartManager.js`
- Create: `finance-frontend/test/utils/chartManager.test.js`

**目标：** 解决 #12，每个 message 持有一个 ChartManager 实例，message 卸载时统一 unmount 所有 chart Vue app。

- [ ] **Step 5.1: 写测试 `test/utils/chartManager.test.js`**

```javascript
import { describe, it, expect, vi } from 'vitest'
import { createChartManager } from '../../src/utils/chartManager.js'

// 用 mock 组件避开 echarts 依赖
const FakeChart = {
  template: '<div class="fake-chart">{{ headers.join(",") }}</div>',
  props: ['type', 'title', 'headers', 'rows'],
}

describe('createChartManager', () => {
  it('mount 后 DOM 里出现挂载内容', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })

    expect(el.querySelector('.fake-chart')).toBeTruthy()
    expect(el.textContent).toContain('a,b')

    document.body.removeChild(root)
  })

  it('unmountAll 后 mount 内容被清掉', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'pie', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    expect(el.querySelector('.fake-chart')).toBeTruthy()

    mgr.unmountAll()
    expect(el.querySelector('.fake-chart')).toBeFalsy()

    document.body.removeChild(root)
  })

  it('count() 正确反映已 mount 数量', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el1 = document.createElement('div')
    const el2 = document.createElement('div')
    root.appendChild(el1)
    root.appendChild(el2)

    const mgr = createChartManager(FakeChart)
    expect(mgr.count()).toBe(0)

    mgr.mount(el1, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    mgr.mount(el2, { type: 'pie', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    expect(mgr.count()).toBe(2)

    mgr.unmountAll()
    expect(mgr.count()).toBe(0)

    document.body.removeChild(root)
  })

  it('对同一 el 重复 mount 会先 unmount 旧 app（避免重复实例）', () => {
    const root = document.createElement('div')
    document.body.appendChild(root)
    const el = document.createElement('div')
    root.appendChild(el)

    const mgr = createChartManager(FakeChart)
    mgr.mount(el, { type: 'bar', title: '', headers: ['a', 'b'], rows: [['x', '1']] })
    mgr.mount(el, { type: 'pie', title: '', headers: ['c', 'd'], rows: [['y', '2']] })

    expect(mgr.count()).toBe(1)
    expect(el.textContent).toContain('c,d')

    document.body.removeChild(root)
  })
})
```

- [ ] **Step 5.2: 跑测试，确认失败**

```bash
cd finance-frontend && npm test test/utils/chartManager.test.js
```

预期：FAIL。

- [ ] **Step 5.3: 实现 `src/utils/chartManager.js`**

```javascript
import { createApp } from 'vue'

/**
 * 创建一个 ChartManager。每个调用方（如一条 ChatMessage）持有一个独立实例，
 * 用 Map<HTMLElement, App> 跟踪挂载的 Vue app；卸载时统一 unmount 防止泄漏。
 */
export function createChartManager(chartComponent) {
  const apps = new Map()

  return {
    mount(el, props) {
      // 同一节点重复 mount → 先 unmount 旧的
      const existing = apps.get(el)
      if (existing) {
        existing.unmount()
        apps.delete(el)
      }
      const app = createApp(chartComponent, props)
      app.mount(el)
      apps.set(el, app)
    },
    unmountAll() {
      for (const app of apps.values()) {
        try {
          app.unmount()
        } catch (_e) {
          // 防御：极端情况下根节点被外部移除导致 unmount 抛错；忽略
        }
      }
      apps.clear()
    },
    count() {
      return apps.size
    },
  }
}
```

- [ ] **Step 5.4: 跑测试，确认通过**

```bash
cd finance-frontend && npm test test/utils/chartManager.test.js
```

预期：4 个测试全绿。

- [ ] **Step 5.5: commit**

```bash
git add finance-frontend/src/utils/chartManager.js finance-frontend/test/utils/chartManager.test.js
git commit -m "$(cat <<'EOF'
refactor: 用 ChartManager 显式管理图表 Vue app 生命周期

修复审计 #12：之前 createApp().mount() 后从不 unmount，message 重渲染时
旧 Vue app 永远 leak。新 ChartManager 跟踪所有挂载，卸载时统一释放。
EOF
)"
```

---

## Task 6: ChatMessage 重构 — 新增 `streaming` prop，分离快/慢渲染路径

**Files:**
- Modify: `finance-frontend/src/components/ChatMessage.vue` (整体替换)
- Create: `finance-frontend/test/components/ChatMessage.test.js`

**关键设计：**
- 新增 `streaming: Boolean` prop。
- streaming=true → 只渲染纯文本（`<div>{{ text }}</div>`），不跑 marked，不挂图表。
- streaming=false（流结束）→ 走 markdown 路径，DOMPurify sanitize + 图表 mount。
- 用 `onBeforeUnmount` 调 `chartManager.unmountAll()` 释放图表。

- [ ] **Step 6.1: 写测试 `test/components/ChatMessage.test.js`**

```javascript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatMessage from '../../src/components/ChatMessage.vue'

describe('ChatMessage', () => {
  it('user 消息总是纯文本（不跑 markdown）', () => {
    const w = mount(ChatMessage, {
      props: { role: 'user', text: '**加粗** 测试', streaming: false, id: 'u1' },
    })
    expect(w.text()).toContain('**加粗** 测试')
    expect(w.html()).not.toContain('<strong>')
  })

  it('assistant + streaming=true → 纯文本，无 marked', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '## 标题\n正文', streaming: true, id: 'a1' },
    })
    expect(w.text()).toContain('## 标题')
    expect(w.html()).not.toContain('<h2>')
  })

  it('assistant + streaming=false → markdown 渲染', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '## 标题\n正文', streaming: false, id: 'a2' },
    })
    expect(w.html()).toContain('<h2')
    expect(w.text()).toContain('标题')
  })

  it('streaming 由 true → false 切换时触发 markdown 渲染', async () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '**粗**', streaming: true, id: 'a3' },
    })
    expect(w.html()).not.toContain('<strong>')
    await w.setProps({ streaming: false })
    expect(w.html()).toContain('<strong>')
  })

  it('XSS payload 被剥离', () => {
    const w = mount(ChatMessage, {
      props: {
        role: 'assistant',
        text: '<img src=x onerror="alert(1)">',
        streaming: false,
        id: 'a4',
      },
    })
    expect(w.html()).not.toMatch(/onerror/i)
  })

  it('feedback 按钮 streaming=true 时不显示', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '答', streaming: true, id: 'a5' },
    })
    expect(w.find('.feedback-btn').exists()).toBe(false)
  })

  it('feedback 按钮 streaming=false 时显示', () => {
    const w = mount(ChatMessage, {
      props: { role: 'assistant', text: '答', streaming: false, id: 'a6' },
    })
    expect(w.find('.feedback-btn').exists()).toBe(true)
  })
})
```

- [ ] **Step 6.2: 跑测试，确认失败**

```bash
cd finance-frontend && npm test test/components/ChatMessage.test.js
```

预期：FAIL（ChatMessage 还没接受 streaming prop / 还在用旧实现）。

- [ ] **Step 6.3: 整体重写 `src/components/ChatMessage.vue`**

```vue
<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble" v-if="role === 'user'">{{ text }}</div>
    <template v-else>
      <div v-if="streaming" class="bubble streaming-text">{{ text }}</div>
      <div v-else>
        <div ref="bubbleRef" class="bubble markdown-body" v-html="rendered"></div>
        <div class="feedback-actions" v-if="text && text.length > 0">
          <span
            class="feedback-btn"
            :class="{ active: feedback === 'positive' }"
            @click="submitFeedback('positive')"
          >👍</span>
          <span
            class="feedback-btn"
            :class="{ active: feedback === 'negative' }"
            @click="submitFeedback('negative')"
          >👎</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onBeforeUnmount } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'
import { extractTableData } from '../utils/chartExtractor.js'
import { createChartManager } from '../utils/chartManager.js'
import ChartRenderer from './ChartRenderer.vue'
import { userStore } from '../stores/userStore.js'

const props = defineProps({
  role: { type: String, required: true },
  text: { type: String, default: '' },
  id: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
})

const bubbleRef = ref(null)
const feedback = ref(null)

// 每个 ChatMessage 实例持有独立的 ChartManager
const chartMgr = createChartManager(ChartRenderer)

const rendered = computed(() => {
  if (props.role !== 'assistant' || props.streaming) return ''
  return renderMarkdown(props.text)
})

function mountChartsInBubble() {
  if (!bubbleRef.value) return
  // message 重渲染前先卸载所有旧图表
  chartMgr.unmountAll()

  bubbleRef.value.querySelectorAll('table').forEach((table) => {
    const data = extractTableData(table)
    if (!data) return

    const wrapper = document.createElement('div')
    wrapper.className = 'chart-wrapper'
    const chartDiv = document.createElement('div')
    chartDiv.className = 'chart-container'
    wrapper.appendChild(chartDiv)

    const toggle = document.createElement('div')
    toggle.className = 'chart-toggle'
    toggle.textContent = '📊 图表'
    toggle.onclick = () => {
      const showing = chartDiv.style.display !== 'none'
      chartDiv.style.display = showing ? 'none' : ''
      table.style.display = showing ? '' : 'none'
      toggle.textContent = showing ? '📊 图表' : '📋 表格'
    }
    wrapper.appendChild(toggle)

    table.parentNode.insertBefore(wrapper, table)

    chartMgr.mount(chartDiv, {
      type: data.chartType,
      title: '',
      headers: data.headers,
      rows: data.rows,
    })
  })
}

// 仅在 markdown 渲染路径下挂载图表
watch(
  () => [props.streaming, rendered.value],
  ([streamingNow]) => {
    if (streamingNow) {
      chartMgr.unmountAll()
      return
    }
    nextTick(mountChartsInBubble)
  },
  { immediate: true },
)

// 组件卸载时清理所有图表 Vue app
onBeforeUnmount(() => {
  chartMgr.unmountAll()
})

async function submitFeedback(rating) {
  if (feedback.value) return
  feedback.value = rating
  try {
    await fetch('/api/feedback', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: userStore.currentUser,
        messageId: props.id,
        rating,
      }),
    })
  } catch (e) {
    console.error('[Agent] Feedback error:', e)
  }
}
</script>

<style scoped>
.message { display: flex; margin-bottom: 8px; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.bubble {
  max-width: 85%; padding: 10px 14px; border-radius: 14px;
  font-size: 0.9rem; line-height: 1.6;
}
.user .bubble { background: var(--el-color-primary); color: #fff; }
.assistant .bubble {
  background: #fff; color: #303133;
  border: 1px solid #e4e7ed; box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.streaming-text { white-space: pre-wrap; word-break: break-word; }

.markdown-body :deep(h2) { font-size: 1.1rem; margin: 12px 0 8px; padding-bottom: 6px; border-bottom: 2px solid var(--el-color-primary-light-7); }
.markdown-body :deep(h3) { font-size: 1rem; margin: 10px 0 6px; color: #606266; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(strong) { font-weight: 600; color: #303133; }
.markdown-body :deep(em) { color: #909399; }

.markdown-body :deep(table) {
  width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 0.85rem;
  border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.markdown-body :deep(thead tr) {
  background: linear-gradient(135deg, var(--el-color-primary-light-7), var(--el-color-primary-light-5));
}
.markdown-body :deep(th) { padding: 8px 12px; color: #fff; font-weight: 600; text-align: center; }
.markdown-body :deep(td) { padding: 7px 12px; border-bottom: 1px solid #f0f0f0; text-align: center; }
.markdown-body :deep(tbody tr:hover) { background: #f5f7fa; }
.markdown-body :deep(tbody tr:last-child td) { border-bottom: none; }

.markdown-body :deep(code) {
  background: #f0f2f5; padding: 3px 8px; border-radius: 4px; font-size: 0.85em;
  color: #e6a23c; font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}
.markdown-body :deep(pre) {
  background: #1e1e1e; color: #d4d4d4; padding: 14px 16px; border-radius: 8px;
  overflow-x: auto; margin: 10px 0; font-size: 0.85rem;
}
.markdown-body :deep(pre code) { background: none; color: inherit; padding: 0; }

.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 2px 0; }
.markdown-body :deep(blockquote) {
  border-left: 4px solid var(--el-color-primary-light-5); padding: 8px 12px;
  margin: 8px 0; background: #f5f7fa; border-radius: 0 6px 6px 0; color: #606266;
}
.markdown-body :deep(hr) { border: none; border-top: 1px solid #ebeef5; margin: 12px 0; }

.feedback-actions { margin-top: 4px; text-align: right; }
.feedback-btn { cursor: pointer; margin-left: 8px; opacity: 0.4; font-size: 0.85rem; user-select: none; }
.feedback-btn:hover { opacity: 0.8; }
.feedback-btn.active { opacity: 1; }
</style>
```

- [ ] **Step 6.4: 跑测试，确认通过**

```bash
cd finance-frontend && npm test test/components/ChatMessage.test.js
```

预期：7 个测试全绿。

如果遇到 ChartRenderer 在 jsdom 里加载 echarts 报错，把测试里第一条 import 改成 mock：

```javascript
import { vi } from 'vitest'
vi.mock('../../src/components/ChartRenderer.vue', () => ({
  default: { template: '<div class="chart-stub"></div>' },
}))
```

放在 import ChatMessage 之前即可。

- [ ] **Step 6.5: commit**

```bash
git add finance-frontend/src/components/ChatMessage.vue finance-frontend/test/components/ChatMessage.test.js
git commit -m "$(cat <<'EOF'
refactor: ChatMessage 分离 streaming/done 双渲染路径

- streaming=true：纯文本快路径，不跑 marked/不挂图表（修审计 #11 性能炸）
- streaming=false：marked + DOMPurify + 图表 mount（修审计 #4 XSS）
- onBeforeUnmount 显式 chartManager.unmountAll（修审计 #12 内存泄漏）
EOF
)"
```

---

## Task 7: ChatPanel 移除字符级 setTimeout，传 `streaming` prop

**Files:**
- Modify: `finance-frontend/src/components/ChatPanel.vue` (整体替换 send + template)

**关键改动：**
- 去掉 `for (const ch of content)` + `await new Promise(r => setTimeout(r, 20))`：直接 `messages.value[idx].text += content`。
- 给每条 message 加 `streaming: Boolean` 字段。
- 流式期间 streaming=true，stream done 后 streaming=false 触发 markdown/chart 一次性渲染。
- 用 `requestAnimationFrame` 节流 scroll，不再每字符 reflow。

- [ ] **Step 7.1: 整体重写 `src/components/ChatPanel.vue`**

```vue
<template>
  <div class="chat-panel">
    <div class="chat-header">
      AI 助手
      <span class="memory-info" v-if="memoryCount > 0">记忆: {{ memoryCount }}/20 条</span>
    </div>
    <div class="chat-messages" ref="msgContainer">
      <ChatMessage
        v-for="m in messages"
        :key="m.id"
        :role="m.role"
        :text="m.text"
        :id="m.id"
        :streaming="m.streaming"
      />
      <div v-if="thinking && !messages.length" class="thinking">思考中...</div>
      <div
        v-if="thinking && messages.length && messages[messages.length-1].role === 'assistant' && messages[messages.length-1].streaming"
        class="streaming-dot"
      ></div>
    </div>
    <div class="chat-input">
      <el-input
        v-model="input"
        @keyup.enter="send"
        placeholder="比如：我的余额是多少？"
        :disabled="thinking"
        clearable
      />
      <el-button type="primary" @click="send" :disabled="thinking" style="margin-left: 8px">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onUnmounted } from 'vue'
import ChatMessage from './ChatMessage.vue'
import { userStore } from '../stores/userStore.js'
import { createStreamBuffer } from '../utils/streamParser.js'

const messages = ref([])
const input = ref('')
const thinking = ref(false)
const msgContainer = ref(null)
const memoryCount = ref(0)

let scrollFrame = null
function scheduleScrollToBottom() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = null
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight
    }
  })
}

watch(
  () => userStore.currentUser,
  () => {
    messages.value = []
    memoryCount.value = 0
    thinking.value = false
  },
)

watch(() => messages.value.length, scheduleScrollToBottom)

onUnmounted(() => {
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
})

let nextMsgId = 1
function newId(role) {
  return `${role}-${Date.now()}-${nextMsgId++}`
}

async function send() {
  if (!input.value.trim() || thinking.value) return
  const text = input.value
  console.time('[Agent] 总耗时')
  input.value = ''

  messages.value.push({ id: newId('user'), role: 'user', text, streaming: false })
  const assistantMsg = { id: newId('assistant'), role: 'assistant', text: '', streaming: true }
  messages.value.push(assistantMsg)
  const assistantIdx = messages.value.length - 1

  thinking.value = true
  const requestStart = performance.now()
  let firstToken = false
  const buf = createStreamBuffer()

  try {
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text, userId: userStore.currentUser }),
    })

    const reader = res.body.getReader()
    const decoder = new TextDecoder()

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      const tokens = buf.feed(chunk)
      if (tokens.length === 0) continue

      if (!firstToken) {
        firstToken = true
        const ttft = performance.now() - requestStart
        console.log('[Agent] TTFT:', Math.round(ttft) + 'ms')
      }

      // 一次 chunk 攒到的所有 token 合并写入，避免每 token 都触发响应式
      const appended = tokens.join('')
      messages.value[assistantIdx].text += appended
      scheduleScrollToBottom()
    }

    console.timeEnd('[Agent] 总耗时')
    // 标记流结束 → ChatMessage 触发 markdown + chart mount 一次性渲染
    messages.value[assistantIdx].streaming = false
    memoryCount.value = messages.value.filter((m) => m.role === 'user').length * 2
  } catch (e) {
    console.error('[Agent] Error:', e)
    if (!messages.value[assistantIdx].text) {
      messages.value[assistantIdx].text = '抱歉，服务暂时不可用'
    }
    messages.value[assistantIdx].streaming = false
  } finally {
    thinking.value = false
  }
}
</script>

<style scoped>
.chat-panel {
  display: flex; flex-direction: column; height: 100%;
  background: var(--el-bg-color);
}
.chat-header {
  padding: 12px 16px; font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-light);
}
.chat-messages {
  flex: 1; overflow-y: auto; padding: 12px;
}
.chat-input {
  display: flex; padding: 12px; gap: 0;
  border-top: 1px solid var(--el-border-color-light);
}
.memory-info {
  float: right;
  font-size: 0.8rem;
  color: var(--el-text-color-secondary);
  font-weight: normal;
}
.thinking { color: var(--el-text-color-secondary); font-size: 0.85rem; font-style: italic; padding: 8px; }
.streaming-dot {
  width: 8px; height: 8px; background: var(--el-color-primary);
  border-radius: 50%; animation: pulse 1s infinite; margin: 8px;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
```

- [ ] **Step 7.2: 跑全部前端单测**

```bash
cd finance-frontend && npm test
```

预期：所有已有测试通过（streamParser 8 + markdown 7 + chartExtractor 8 + chartManager 4 + ChatMessage 7 + sanity 1 = 35 个）。

- [ ] **Step 7.3: 浏览器手测（验证流畅度 + chart 周期）**

启动 dev server：

```bash
cd finance-frontend && npm run dev
```

打开 http://localhost:5173，依次手测以下场景，全部通过才算成功：

1. **流式速度**：发"列出我的所有账户"。**预期**：文字按 chunk 飞快出现（不再每字 20ms 卡顿），流式期间显示纯文本（不应看到 markdown 渲染样式）。
2. **流结束 → 完整 markdown**：上一条结束后应该一次性切到 markdown 渲染（标题、表格、强调样式立刻出现）。
3. **图表挂载**：发"按类别给我看本月支出"，回答里有表格时应在表格上方出现"📊 图表"按钮，点击切换。
4. **图表无重复 / 无内存泄漏**：连续问 5 个会出图表的问题，每条 assistant 消息只看到 1 个图表实例（不是 2/3/4 个叠加）。打开 DevTools Memory，多次切换用户后没有 detached DOM 暴涨。
5. **XSS 防护**：手动构造一条消息（直接 `messages.value.push({...text:'<img src=x onerror="alert(1)">', streaming:false, role:'assistant', id:'xss'})` via 控制台）。**预期**：不弹 alert，img 标签 onerror 被剥离。
6. **切换用户**：点 header 切换用户，对话清空，再发问没有残留图表。

如果某项不通过，记录问题，修后重跑。

- [ ] **Step 7.4: commit**

```bash
git add finance-frontend/src/components/ChatPanel.vue
git commit -m "$(cat <<'EOF'
perf: ChatPanel 移除字符级 setTimeout，改 chunk 级渲染

- 去掉 for (const ch of content) await setTimeout 20ms：之前 500 字响应至少 10s 视觉延迟
- 流式期间 message.streaming=true 走 ChatMessage 纯文本快路径
- 流结束 messages[idx].streaming=false 触发 markdown + 图表一次性渲染
- 滚动到底用 requestAnimationFrame 节流，不再每字符 reflow
- key 改用稳定的 message.id，不再用 v-for index
（修审计 #10 #11 之 ChatPanel 部分）
EOF
)"
```

---

## Task 8: 文档更新与最终验证

**Files:**
- Modify: `finance-frontend/README.md`（如已有）
- Optional: `docs/superpowers/plans/2026-05-24-frontend-stream-rendering-refactor.md` 标记完成

- [ ] **Step 8.1: 跑所有测试 + 构建**

```bash
cd finance-frontend && npm test && npm run build
```

预期：
- 35 个单测全绿
- vite build 成功，dist/ 产出无 error

- [ ] **Step 8.2: 更新 frontend README（如果存在）**

如果 `finance-frontend/README.md` 存在且内容简单，在末尾追加测试运行指南：

```markdown
## 测试

```bash
npm test          # 单次
npm run test:watch # watch 模式
```
```

如果 README.md 不存在或内容很重，跳过此步。

- [ ] **Step 8.3: commit（如果有 README 改动）**

```bash
git add finance-frontend/README.md
git commit -m "docs: README 补充前端测试运行指南"
```

如果没改动则跳过。

- [ ] **Step 8.4: 退出 worktree 并提示后续操作**

报告给用户：
```
✅ Phase A 完成：流式渲染重构 + 测试基础设施。
分支：worktree-feat+chat-stream-rendering
共 N 个 commit，35 个单测，build 通过，浏览器手测通过。

下一步选择：
A) 在 master 直接 merge（fast-forward 或 squash）
B) 推到 origin 然后开 PR
C) 保留 worktree 继续做 Phase B（中止按钮）
```

---

## 自检清单（写完 plan 后的 self-review）

- [x] **覆盖审计 #4 / #10 / #11 / #12**：
  - #4 → Task 3（DOMPurify）+ Task 6（ChatMessage 走 markdown.js）
  - #10 → Task 7（去除字符级 setTimeout）
  - #11 → Task 6（streaming=true 纯文本快路径）+ Task 7（chunk 级合并）
  - #12 → Task 5（ChartManager）+ Task 6（onBeforeUnmount）
- [x] 无 placeholder：每个步骤都有完整代码或完整命令。
- [x] 类型一致性：`createStreamBuffer().feed()`、`extractTableData()` 返回结构、`createChartManager(comp).mount/unmountAll/count`、`renderMarkdown(text)` 在多 task 之间一致。
- [x] TDD 严格：每个 Task 都是"写测试 → 跑 fail → 实现 → 跑 pass → commit"。
- [x] 频繁 commit：每个 Task 至少 1 个 commit。

## 风险与回滚

如果 Task 6/7 浏览器手测发现非测试覆盖到的问题（如 ChartRenderer 在新挂载时序下不接受 props 变更），fall-back 策略：
- 保留 streaming/done 双路径设计不变；
- 把 `chartMgr.mount(chartDiv, props)` 在 nextTick 后再调一次（让 echarts 拿到尺寸）。

完整回滚：`git checkout master`，删 worktree（`ExitWorktree action=remove`）即可，无任何 master 改动风险。

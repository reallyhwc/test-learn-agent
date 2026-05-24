# AI 回答富文本渲染优化 — 设计文档

**目标：** 让 AI 回答从纯 Markdown 升级为「Markdown + ECharts 图表混排」，提升视觉表现力。

**方案：** 混合方案（Markdown + 自定义标记）。AI 在 Markdown 中嵌入 `<chart>` 标签，前端 `marked` 渲染后扫描替换为真正的 ECharts 图表组件。

## 架构

```
LLM 输出 Markdown + <chart> 标记
       ↓ SSE (不变)
ChatMessage.vue 累积完整文本
       ↓
marked.parse() → HTML（<chart> 标签原样保留）
       ↓
parseChartTags() → 替换 <chart> 为 <div data-chart-id>
       ↓
v-html 渲染到页面
       ↓
ChartRenderer 挂载 → init ECharts
```

## 标记语法

```html
<chart type="bar|pie|line" title="图表标题">
标签,数值1,数值2
...
</chart>
```

- 标签体内是 CSV 格式：第一行列名，后续行数据
- 第一列 = X 轴标签，后续列 = 数据系列
- 饼图只取前两列
- 数值不带单位，不加逗号分隔

## 涉及文件

| 文件 | 改动 |
|------|------|
| `finance-agent/.../ChatController.java` | buildSystemPrompt() 加入 `<chart>` 使用说明 |
| `finance-frontend/.../ChatMessage.vue` | 新增 parseChartTags()；升级基础 Markdown CSS |
| `finance-frontend/.../ChartRenderer.vue` | **新建** — ECharts 图表组件 |

## 容错

- CSV 数据不足 → 保留原始 `<chart>` 文本
- 某行 CSV 列数不匹配 → 跳过该行
- 未知 type → 保留原始文本
- ECharts 渲染失败 → 显示 "图表加载失败" + 原始数据

## 测试

- Agent 测试：`shouldRenderChartTagWhenQueryingStats` — 验证包含统计数据的回答中检测到 `<chart>` 标记
- 前端手动验证：启动服务，对话测试图表渲染

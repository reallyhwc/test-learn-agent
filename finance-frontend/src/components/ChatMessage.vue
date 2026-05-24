<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble" v-if="role === 'user'">{{ text }}</div>
    <div v-else>
      <div ref="bubbleRef" class="bubble markdown-body" v-html="rendered"></div>
      <div class="feedback-actions" v-if="text && text.length > 0">
        <span class="feedback-btn" :class="{ active: feedback === 'positive' }"
              @click="submitFeedback('positive')">👍</span>
        <span class="feedback-btn" :class="{ active: feedback === 'negative' }"
              @click="submitFeedback('negative')">👎</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { marked } from 'marked'
import { createApp } from 'vue'
import { userStore } from '../stores/userStore.js'
import ChartRenderer from './ChartRenderer.vue'

const props = defineProps({ role: String, text: String, id: String })

const bubbleRef = ref(null)
let chartIdCounter = 0
const chartConfigs = new Map()

const rendered = computed(() => {
  if (props.role !== 'assistant') return props.text
  chartConfigs.clear()
  chartIdCounter = 0
  return marked.parse(props.text || '')
})

function isNumeric(val) {
  return !isNaN(parseFloat(val.replace(/[¥,%,\s]/g, '')))
}

function extractTableData(table) {
  const headers = []
  const thead = table.querySelector('thead')
  if (thead) {
    thead.querySelectorAll('th').forEach(th => headers.push(th.textContent.trim()))
  } else {
    const firstRow = table.querySelector('tr')
    if (firstRow) firstRow.querySelectorAll('td,th').forEach(c => headers.push(c.textContent.trim()))
  }
  if (headers.length < 2) return null

  const rows = []
  const tbody = table.querySelector('tbody') || table
  tbody.querySelectorAll('tr').forEach(tr => {
    const cells = [...tr.querySelectorAll('td,th')].map(c => c.textContent.trim())
    if (cells.length >= 2) rows.push(cells)
  })
  if (rows.length === 0) return null

  // 找数值列（第二列开始）
  const numCols = []
  for (let i = 1; i < headers.length; i++) {
    const sample = rows.slice(0, 3).filter(r => r[i]).map(r => r[i])
    if (sample.length > 0 && sample.every(isNumeric)) {
      numCols.push(i)
    }
  }
  if (numCols.length === 0) return null

  // 清洗：标签列保留原文，数值列 parseFloat
  const cleanRows = rows.map(r => {
    return r.map((c, i) => {
      if (i === 0) return c
      if (numCols.includes(i)) {
        const n = parseFloat(c.replace(/[¥,%,\s]/g, ''))
        return isNaN(n) ? '0' : String(n)
      }
      return c
    })
  }).filter(r => r.length >= 2)

  if (cleanRows.length === 0) return null

  // 判别图表类型：2 列 + ≤8 行 → pie，否则 bar
  const hasMultipleNumericCols = numCols.length > 1
  return {
    headers: headers.filter((_, i) => i === 0 || numCols.includes(i)),
    rows: cleanRows.map(r => r.filter((_, i) => i === 0 || numCols.includes(i))),
    chartType: hasMultipleNumericCols ? 'bar' : (cleanRows.length <= 8 ? 'pie' : 'bar')
  }
}

function mountCharts() {
  if (!bubbleRef.value) return
  const tables = bubbleRef.value.querySelectorAll('table')
  tables.forEach(table => {
    if (table.dataset.chartMounted) return
    table.dataset.chartMounted = '1'

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
      if (chartDiv.style.display === 'none') {
        chartDiv.style.display = ''
        table.style.display = ''
        toggle.textContent = '📋 表格'
      } else {
        chartDiv.style.display = 'none'
        table.style.display = 'none'
        toggle.textContent = '📊 图表'
      }
    }
    wrapper.appendChild(toggle)

    table.parentNode.insertBefore(wrapper, table)

    const id = 'chart-' + (chartIdCounter++)
    chartConfigs.set(id, {
      type: data.chartType,
      title: '',
      headers: data.headers,
      rows: data.rows
    })
    chartDiv.dataset.chartId = id
  })

  // 挂载 ChartRenderer
  document.querySelectorAll('.chart-container').forEach(el => {
    const cid = el.dataset.chartId
    if (!cid || el.dataset.mounted) return
    el.dataset.mounted = '1'
    const config = chartConfigs.get(cid)
    if (!config) return
    try {
      createApp(ChartRenderer, {
        type: config.type,
        title: config.title,
        headers: config.headers,
        rows: config.rows
      }).mount(el)
    } catch (e) {
      console.error('[ChartRenderer] Mount failed:', e)
    }
  })
}

watch(() => props.text, () => nextTick(mountCharts))
watch(rendered, () => nextTick(mountCharts))

const feedback = ref(null)

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
        rating: rating
      })
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

/* 增强 Markdown 样式 */
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
.markdown-body :deep(th) {
  padding: 8px 12px; color: #fff; font-weight: 600; text-align: center;
}
.markdown-body :deep(td) {
  padding: 7px 12px; border-bottom: 1px solid #f0f0f0; text-align: center;
}
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

/* 图表相关样式 */
.chart-wrapper { margin: 12px 0; }
.chart-container { margin-bottom: 8px; }
.chart-toggle {
  display: inline-block; font-size: 0.8rem; color: var(--el-color-primary);
  cursor: pointer; user-select: none; padding: 2px 6px; border-radius: 4px;
}
.chart-toggle:hover { background: var(--el-color-primary-light-9); }

.feedback-actions { margin-top: 4px; text-align: right; }
.feedback-btn { cursor: pointer; margin-left: 8px; opacity: 0.4; font-size: 0.85rem; user-select: none; }
.feedback-btn:hover { opacity: 0.8; }
.feedback-btn.active { opacity: 1; }
</style>

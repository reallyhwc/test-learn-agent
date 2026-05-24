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

function parseChartTags(html) {
  const regex = /<chart\s+type="(bar|pie|line)"\s+title="(.*?)">([\s\S]*?)<\/chart>/g

  return html.replace(regex, (match, type, title, csvBody) => {
    const lines = csvBody.trim().split('\n').filter(l => l.trim())
    if (lines.length < 2) return match

    const headers = lines[0].split(',').map(h => h.trim())
    const rows = lines.slice(1).map(l => l.split(',').map(c => c.trim()))

    if (rows.length === 0) return match

    const id = 'chart-' + (chartIdCounter++)
    chartConfigs.set(id, { type, title, headers, rows })
    return `<div class="chart-container" data-chart-id="${id}"></div>`
  })
}

const rendered = computed(() => {
  if (props.role !== 'assistant') return props.text
  chartConfigs.clear()
  chartIdCounter = 0
  const html = marked.parse(props.text || '')
  return parseChartTags(html)
})

function mountCharts() {
  if (!bubbleRef.value) return
  const containers = bubbleRef.value.querySelectorAll('.chart-container')
  containers.forEach(el => {
    const id = el.dataset.chartId
    const config = chartConfigs.get(id)
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
      el.innerHTML = '<div class="chart-error">图表加载失败</div>'
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

.feedback-actions { margin-top: 4px; text-align: right; }
.feedback-btn { cursor: pointer; margin-left: 8px; opacity: 0.4; font-size: 0.85rem; user-select: none; }
.feedback-btn:hover { opacity: 0.8; }
.feedback-btn.active { opacity: 1; }
</style>

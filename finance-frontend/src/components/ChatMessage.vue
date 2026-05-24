<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble" v-if="role === 'user'">{{ text }}</div>
    <template v-else>
      <!-- LLM 思考过程：流式期间默认展开实时显示，结束后默认折叠 -->
      <div v-if="thinking && thinking.length > 0" class="thinking-wrapper">
        <div class="thinking-header" @click="thinkingCollapsed = !thinkingCollapsed">
          <span class="thinking-icon">💭</span>
          <span class="thinking-label">
            {{ streaming ? '思考中…' : '思考过程' }}
          </span>
          <span class="thinking-toggle">{{ thinkingCollapsed ? '▶ 展开' : '▼ 收起' }}</span>
        </div>
        <div v-show="!thinkingCollapsed" class="thinking-body">{{ thinking }}</div>
      </div>

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
import { useUserStore } from '../stores/userStore.js'
import { apiPost } from '../utils/api.js'

const userStore = useUserStore()

const props = defineProps({
  role: { type: String, required: true },
  text: { type: String, default: '' },
  thinking: { type: String, default: '' },
  id: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
})

const bubbleRef = ref(null)
const feedback = ref(null)
// 默认折叠 — 大多数 reasoning 对用户没价值，需要时点 ▶ 展开看
const thinkingCollapsed = ref(true)

const chartMgr = createChartManager(ChartRenderer)

const rendered = computed(() => {
  if (props.role !== 'assistant' || props.streaming) return ''
  return renderMarkdown(props.text)
})

function mountChartsInBubble() {
  if (!bubbleRef.value) return
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

onBeforeUnmount(() => {
  chartMgr.unmountAll()
})

async function submitFeedback(rating) {
  if (feedback.value) return
  feedback.value = rating
  try {
    await apiPost('/api/feedback', {
      userId: userStore.currentUser,
      messageId: props.id,
      rating,
    })
  } catch (e) {
    console.error('[Feedback] 提交失败:', e)
    feedback.value = null
  }
}
</script>

<style scoped>
.message { display: flex; flex-direction: column; margin-bottom: 8px; }
.user { align-items: flex-end; }
.assistant { align-items: flex-start; }
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

/* 思考过程折叠区 */
.thinking-wrapper {
  max-width: 85%;
  margin-bottom: 6px;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  font-size: 0.8rem;
  color: #909399;
  overflow: hidden;
}
.thinking-header {
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}
.thinking-header:hover { background: #f0f2f5; }
.thinking-icon { font-size: 0.9rem; }
.thinking-label { flex: 1; }
.thinking-toggle { font-size: 0.75rem; opacity: 0.7; }
.thinking-body {
  padding: 8px 10px;
  border-top: 1px dashed #ebeef5;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  font-size: 0.78rem;
  line-height: 1.5;
  max-height: 240px;
  overflow-y: auto;
}

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

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
  max-width: 85%; padding: 10px 14px;
  font-size: 0.9rem; line-height: 1.6;
}
/* 用户气泡：紫色渐变 + 右下角小圆角 */
.user .bubble {
  background: var(--theme-primary-gradient);
  color: var(--theme-text-on-primary);
  border-radius: var(--theme-radius-bubble) var(--theme-radius-bubble) 6px var(--theme-radius-bubble);
}
/* AI 气泡：浅灰/深灰 + 左下角小圆角 */
.assistant .bubble {
  background: var(--theme-bg-msg-ai);
  color: var(--theme-text-primary);
  border: 1px solid var(--theme-border);
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
  border-radius: var(--theme-radius-bubble) var(--theme-radius-bubble) var(--theme-radius-bubble) 6px;
}
.streaming-text { white-space: pre-wrap; word-break: break-word; }

/* 思考过程折叠区 */
.thinking-wrapper {
  max-width: 85%;
  margin-bottom: 6px;
  background: var(--theme-bg-thinking);
  border: 1px solid var(--theme-border-light);
  border-radius: 8px;
  font-size: 0.8rem;
  color: var(--theme-text-muted);
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
.thinking-header:hover { background: var(--theme-bg-table-hover); }
.thinking-icon { font-size: 0.9rem; }
.thinking-label { flex: 1; }
.thinking-toggle { font-size: 0.75rem; opacity: 0.7; }
.thinking-body {
  padding: 8px 10px;
  border-top: 1px dashed var(--theme-border-light);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  font-size: 0.78rem;
  line-height: 1.5;
  max-height: 240px;
  overflow-y: auto;
  color: var(--theme-text-secondary);
}

/* Markdown 内容 */
.markdown-body :deep(h2) {
  font-size: 1.1rem; margin: 12px 0 8px; padding-bottom: 6px;
  border-bottom: 2px solid var(--theme-primary-lighter);
}
.markdown-body :deep(h3) { font-size: 1rem; margin: 10px 0 6px; color: var(--theme-text-secondary); }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(strong) { font-weight: 600; color: var(--theme-text-primary); }
.markdown-body :deep(em) { color: var(--theme-text-muted); }

.markdown-body :deep(table) {
  width: 100%; border-collapse: collapse; margin: 10px 0; font-size: 0.85rem;
  border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.markdown-body :deep(thead tr) { background: var(--theme-primary-gradient); }
.markdown-body :deep(th) { padding: 8px 12px; color: var(--theme-text-on-primary); font-weight: 600; text-align: center; }
.markdown-body :deep(td) { padding: 7px 12px; border-bottom: 1px solid var(--theme-border-light); text-align: center; color: var(--theme-text-primary); }
.markdown-body :deep(tbody tr:hover) { background: var(--theme-bg-table-hover); }
.markdown-body :deep(tbody tr:last-child td) { border-bottom: none; }

.markdown-body :deep(code) {
  background: var(--theme-bg-input); padding: 3px 8px; border-radius: 4px; font-size: 0.85em;
  color: var(--theme-warning); font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}
.markdown-body :deep(pre) {
  background: var(--theme-bg-code); color: #d4d4d4; padding: 14px 16px; border-radius: 8px;
  overflow-x: auto; margin: 10px 0; font-size: 0.85rem;
}
.markdown-body :deep(pre code) { background: none; color: inherit; padding: 0; }

.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 2px 0; }
.markdown-body :deep(blockquote) {
  border-left: 4px solid var(--theme-primary);
  padding: 8px 12px; margin: 8px 0;
  background: var(--theme-primary-bg);
  border-radius: 0 6px 6px 0;
  color: var(--theme-text-secondary);
}
.markdown-body :deep(hr) { border: none; border-top: 1px solid var(--theme-border-light); margin: 12px 0; }
.markdown-body :deep(a) { color: var(--theme-primary); text-decoration: none; }
.markdown-body :deep(a:hover) { text-decoration: underline; }

.feedback-actions { margin-top: 4px; text-align: right; }
.feedback-btn { cursor: pointer; margin-left: 8px; opacity: 0.4; font-size: 0.85rem; user-select: none; color: var(--theme-text-muted); }
.feedback-btn:hover { opacity: 0.8; }
.feedback-btn.active { opacity: 1; }
</style>

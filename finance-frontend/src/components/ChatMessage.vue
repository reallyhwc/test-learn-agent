<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble" v-if="role === 'user'">{{ text }}</div>
    <div class="bubble markdown-body" v-else v-html="rendered"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'

const props = defineProps({ role: String, text: String })

const rendered = computed(() => {
  if (props.role !== 'assistant') return props.text
  return marked.parse(props.text || '')
})
</script>

<style scoped>
.message { display: flex; margin-bottom: 8px; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.bubble {
  max-width: 85%; padding: 8px 12px; border-radius: 12px;
  font-size: 0.9rem; line-height: 1.5;
}
.user .bubble { background: var(--el-color-primary); color: #fff; }
.assistant .bubble { background: var(--el-color-info-light-9); color: #333; }
.markdown-body :deep(table) { width: 100%; border-collapse: collapse; margin: 8px 0; font-size: 0.85rem; }
.markdown-body :deep(th) { background: var(--el-color-primary-light-9); padding: 6px 8px; border: 1px solid #ddd; }
.markdown-body :deep(td) { padding: 4px 8px; border: 1px solid #ddd; }
.markdown-body :deep(code) { background: rgba(0,0,0,0.06); padding: 2px 6px; border-radius: 3px; font-size: 0.85em; }
.markdown-body :deep(strong) { font-weight: 600; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 20px; margin: 4px 0; }
.markdown-body :deep(p) { margin: 4px 0; }
</style>

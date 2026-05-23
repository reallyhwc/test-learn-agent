<template>
  <div class="message" :class="{ user: role === 'user', assistant: role === 'assistant' }">
    <div class="bubble">{{ text }}</div>
  </div>
</template>

<script setup>
defineProps({ role: String, text: String })
</script>

<style scoped>
.message { display: flex; margin-bottom: 8px; }
.user { justify-content: flex-end; }
.assistant { justify-content: flex-start; }
.bubble {
  max-width: 80%; padding: 8px 12px; border-radius: 12px;
  font-size: 0.9rem; line-height: 1.4;
}
.user .bubble { background: #3498db; color: #fff; }
.assistant .bubble { background: #f0f0f0; color: #333; }
</style>

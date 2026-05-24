<template>
  <div v-if="error" class="error-boundary">
    <el-result icon="error" title="页面出现错误" :sub-title="error.message">
      <template #extra>
        <el-button type="primary" @click="recover">重新加载</el-button>
      </template>
    </el-result>
  </div>
  <slot v-else />
</template>

<script setup>
import { ref, onErrorCaptured } from 'vue'

const error = ref(null)

onErrorCaptured((err) => {
  error.value = err
  console.error('[ErrorBoundary]', err)
  return false
})

function recover() {
  error.value = null
}
</script>

<style scoped>
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>

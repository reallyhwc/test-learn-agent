<template>
  <el-header class="app-header">
    <div class="header-left">
      <div class="logo-icon">💰</div>
      <div class="logo-text">
        <h1>Finance Agent</h1>
        <span class="subtitle">智能记账助手</span>
      </div>
    </div>
    <div class="header-right">
      <div class="combo-switch">
        <el-select
          :model-value="aiStore.agentType"
          @change="onAgentChange"
          size="small"
          class="provider-select"
          placeholder="Agent"
        >
          <el-option value="java" label="Java Agent" />
          <el-option value="python" label="Python Agent" />
        </el-select>
        <span class="combo-divider">+</span>
        <el-select
          :model-value="aiStore.mcpType"
          @change="onMcpChange"
          size="small"
          class="provider-select"
          :loading="aiStore.mcpSwitching"
          :disabled="aiStore.mcpSwitching"
          placeholder="MCP"
        >
          <el-option value="java" label="Java MCP" />
          <el-option value="python" label="Python MCP" />
        </el-select>
      </div>
      <el-select v-model="userStore.currentUser" size="small" class="user-select">
        <el-option v-for="u in userStore.users" :key="u.id" :label="u.name" :value="u.id" />
      </el-select>
      <button class="theme-toggle" @click="userStore.toggleTheme" :aria-label="themeLabel">
        {{ userStore.theme === 'light' ? '🌙' : '☀️' }}
      </button>
    </div>
  </el-header>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/userStore.js'
import { useAiStore } from '../stores/aiStore.js'

const userStore = useUserStore()
const aiStore = useAiStore()
const themeLabel = computed(() =>
  userStore.theme === 'light' ? '切换暗色模式' : '切换亮色模式'
)

async function onAgentChange(type) {
  try {
    await aiStore.switchAgent(type)
  } catch (e) {
    ElMessage.error('Agent 切换失败: ' + (e.message || '未知错误'))
  }
}

async function onMcpChange(type) {
  try {
    await aiStore.switchMcp(type)
    ElMessage.success(`已切换到 ${aiStore.mcpLabel}`)
  } catch (e) {
    ElMessage.error('MCP 切换失败: ' + (e.message || '未知错误'))
  }
}
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--theme-bg-header);
  color: var(--theme-text-primary);
  height: 60px;
  box-shadow: var(--theme-shadow-header);
  transition: background var(--theme-transition), color var(--theme-transition), box-shadow var(--theme-transition);
}

.header-left { display: flex; align-items: center; gap: 12px; }

.logo-icon {
  width: 36px; height: 36px;
  background: var(--theme-primary-gradient);
  border-radius: var(--theme-radius-icon);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
  box-shadow: 0 2px 8px var(--theme-primary-shadow);
}

.logo-text { display: flex; flex-direction: column; }
.logo-text h1 { font-size: 1.1rem; font-weight: 700; line-height: 1.2; }
.subtitle { color: var(--theme-text-muted); font-size: 0.75rem; }

.header-right { display: flex; align-items: center; gap: 10px; }

.user-select { width: 120px; }
.user-select :deep(.el-input__wrapper) {
  border-radius: var(--theme-radius-tag);
  background: var(--theme-bg-input);
  box-shadow: none;
  border: 1px solid var(--theme-border);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}

.theme-toggle {
  width: 36px; height: 36px;
  border: 1px solid var(--theme-border);
  border-radius: 50%;
  background: var(--theme-bg-input);
  font-size: 18px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background var(--theme-transition), border-color var(--theme-transition), transform 0.2s;
}
.theme-toggle:hover { transform: scale(1.1); }

.combo-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--theme-bg-input);
  border-radius: var(--theme-radius-tag);
  border: 1px solid var(--theme-border);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}
.combo-divider {
  font-size: 0.8rem;
  color: var(--theme-text-muted);
  font-weight: 600;
}
.provider-select {
  width: 130px;
}
.provider-select :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  border: none;
  padding: 0 4px;
}
</style>

<template>
  <el-header class="app-header">
    <div class="header-left">
      <h1>Personal Finance Agent</h1>
      <span class="subtitle">记账 · AI 助手</span>
      <span class="ai-badge" @click="showAiInfo = true">
        {{ aiStore.getAgentLabel(aiStore.agentType) }}
      </span>
    </div>
    <div class="header-right">
      <span class="user-label">用户：</span>
      <el-select v-model="userStore.currentUser" size="small" style="width: 120px">
        <el-option v-for="u in userStore.users" :key="u.id" :label="u.name" :value="u.id" />
      </el-select>
    </div>

    <!-- AI 配置信息弹窗 -->
    <el-dialog v-model="showAiInfo" title="AI 服务配置" width="420px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="Agent 提供者">
          <el-tag :type="aiStore.agentType === 'python' ? 'success' : ''">
            {{ aiStore.getAgentLabel(aiStore.agentType) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="MCP Server">
          <el-tag :type="aiStore.mcpType === 'python' ? 'success' : ''">
            {{ aiStore.mcpType === 'python' ? 'Python (FastMCP)' : 'Java (Spring AI MCP)' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Agent 端口">
          {{ aiStore.agentPort }}
        </el-descriptions-item>
        <el-descriptions-item label="可用 Agent">
          {{ aiStore.availableAgents.map(a => aiStore.getAgentLabel(a)).join('、') }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <span class="dialog-footer">
          <el-text type="info" size="small">
            修改 AI 提供者请编辑根目录 config.yaml，然后重启服务
          </el-text>
        </span>
      </template>
    </el-dialog>
  </el-header>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useUserStore } from '../stores/userStore.js'
import { useAiStore } from '../stores/aiStore.js'

const userStore = useUserStore()
const aiStore = useAiStore()
const showAiInfo = ref(false)

onMounted(() => {
  aiStore.fetchConfig()
})
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #1a1a2e;
  color: #fff;
  height: 56px;
}
.header-left { display: flex; align-items: center; gap: 12px; }
.header-left h1 { font-size: 1.1rem; font-weight: 600; }
.subtitle { color: #888; font-size: 0.85rem; }
.header-right { display: flex; align-items: center; gap: 8px; }
.user-label { color: #ccc; font-size: 0.85rem; }

.ai-badge {
  display: inline-block;
  padding: 2px 10px;
  font-size: 12px;
  color: #a0c4ff;
  background: rgba(64, 158, 255, 0.15);
  border: 1px solid rgba(64, 158, 255, 0.3);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.ai-badge:hover {
  background: rgba(64, 158, 255, 0.25);
}
</style>

<template>
  <ErrorBoundary>
    <div class="app">
      <AppHeader />
      <div v-if="isMobile" class="mobile-tab-bar">
        <button :class="{ active: mobileTab === 'main' }" @click="mobileTab = 'main'" aria-label="财务数据">
          📊 数据
        </button>
        <button :class="{ active: mobileTab === 'chat' }" @click="mobileTab = 'chat'" aria-label="AI助手">
          💬 助手
        </button>
      </div>
      <el-container class="main-layout">
        <el-main v-show="!isMobile || mobileTab === 'main'" class="content-area">
          <AccountList />
          <TransactionForm @saved="refreshTx" />
          <TransactionList ref="txList" />
          <ChartPanel />
        </el-main>
        <el-aside
          :width="isMobile ? '100%' : '400px'"
          class="chat-area"
          :class="{ 'mobile-chat': isMobile }"
          v-show="!isMobile || mobileTab === 'chat'"
        >
          <ChatPanel />
        </el-aside>
      </el-container>
    </div>
  </ErrorBoundary>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ErrorBoundary from './components/ErrorBoundary.vue'
import AppHeader from './components/AppHeader.vue'
import AccountList from './components/AccountList.vue'
import TransactionForm from './components/TransactionForm.vue'
import TransactionList from './components/TransactionList.vue'
import ChatPanel from './components/ChatPanel.vue'
import ChartPanel from './components/ChartPanel.vue'

const txList = ref(null)
function refreshTx() { txList.value?.fetchList() }

const isMobile = ref(false)
const mobileTab = ref('main')

function checkMobile() {
  isMobile.value = window.innerWidth < 768
}

onMounted(() => {
  checkMobile()
  window.addEventListener('resize', checkMobile)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; }
.app {
  display: flex; flex-direction: column; height: 100vh; overflow: hidden;
  background: var(--theme-bg-page);
  transition: background var(--theme-transition);
}
.main-layout { flex: 1; overflow: hidden; }
.content-area {
  padding: var(--theme-padding-page);
  overflow-y: auto;
  display: flex; flex-direction: column;
  gap: var(--theme-gap-section);
}
.chat-area {
  width: 400px;
  border-left: 1px solid var(--theme-border-light);
  overflow: hidden;
  background: var(--theme-bg-chat);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}

.mobile-tab-bar {
  display: flex;
  border-bottom: 1px solid var(--theme-border);
  background: var(--theme-bg-card);
  transition: background var(--theme-transition), border-color var(--theme-transition);
}
.mobile-tab-bar button {
  flex: 1;
  padding: 12px;
  border: none;
  background: none;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  color: var(--theme-text-muted);
  transition: color var(--theme-transition), border-color var(--theme-transition);
}
.mobile-tab-bar button.active {
  color: var(--theme-primary);
  border-bottom-color: var(--theme-primary);
}

@media (max-width: 768px) {
  .main-layout { flex-direction: column; }
  .chat-area { width: 100% !important; border-left: none; border-top: 1px solid var(--theme-border); height: 100%; }
  .content-area { padding: 12px; gap: 14px; }
}
</style>

<template>
  <div class="app">
    <AppHeader />
    <el-container class="main-layout">
      <el-main class="content-area">
        <AccountList />
        <TransactionForm @saved="refreshTx" />
        <TransactionList ref="txList" />
        <ChartPanel />
      </el-main>
      <el-aside width="400px" class="chat-area">
        <ChatPanel />
      </el-aside>
    </el-container>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import AccountList from './components/AccountList.vue'
import TransactionForm from './components/TransactionForm.vue'
import TransactionList from './components/TransactionList.vue'
import ChatPanel from './components/ChatPanel.vue'
import ChartPanel from './components/ChartPanel.vue'

const txList = ref(null)
function refreshTx() { txList.value?.fetchList() }
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.app { display: flex; flex-direction: column; min-height: 100vh; }
.main-layout { flex: 1; }
.content-area { padding: 20px; }
.chat-area { min-height: calc(100vh - 60px); border-left: 1px solid var(--el-border-color-light); }
</style>

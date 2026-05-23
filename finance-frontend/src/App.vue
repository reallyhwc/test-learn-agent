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
.app { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.main-layout { flex: 1; overflow: hidden; }
.content-area { padding: 20px; overflow-y: auto; }
.chat-area { width: 400px; border-left: 1px solid var(--el-border-color-light); overflow: hidden; }
</style>

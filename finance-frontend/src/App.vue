<template>
  <div class="app">
    <AppHeader />
    <div class="main-layout">
      <div class="content-area">
        <AccountList />
        <TransactionForm @saved="refreshTx" />
        <TransactionList ref="txList" />
      </div>
      <div class="chat-area">
        <ChatPanel />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import AccountList from './components/AccountList.vue'
import TransactionForm from './components/TransactionForm.vue'
import TransactionList from './components/TransactionList.vue'
import ChatPanel from './components/ChatPanel.vue'

const txList = ref(null)
function refreshTx() { txList.value?.fetchList() }
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.app { display: flex; flex-direction: column; min-height: 100vh; }
.main-layout { display: flex; flex: 1; }
.content-area { flex: 1; padding: 20px; min-width: 0; }
.chat-area { width: 380px; min-height: calc(100vh - 56px); }
</style>

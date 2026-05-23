<template>
  <header class="app-header">
    <h1>Personal Finance Agent</h1>
    <span class="subtitle">记账 · AI 助手</span>
    <div class="spacer"></div>
    <label class="user-label">用户：</label>
    <select v-model="userStore.currentUser" class="user-select">
      <option v-for="u in userStore.users" :key="u.id" :value="u.id">{{ u.name }}</option>
    </select>
  </header>
</template>

<script setup>
import { userStore } from '../stores/userStore.js'
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  background: #1a1a2e;
  color: #fff;
}
.app-header h1 {
  margin: 0;
  font-size: 1.2rem;
}
.subtitle {
  color: #888;
  font-size: 0.85rem;
}
.spacer {
  flex: 1;
}
.user-label {
  color: #ccc;
  font-size: 0.85rem;
}
.user-select {
  padding: 4px 8px;
  border: 1px solid #444;
  border-radius: 4px;
  background: #2a2a3e;
  color: #fff;
  font-size: 0.85rem;
}
</style>

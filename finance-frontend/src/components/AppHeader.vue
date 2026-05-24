<template>
  <el-header class="app-header">
    <div class="header-left">
      <h1>Personal Finance Agent</h1>
      <span class="subtitle">记账 · AI 助手</span>
    </div>
    <div class="header-right">
      <span class="user-label">用户：</span>
      <el-select v-model="userStore.currentUser" size="small" style="width: 120px">
        <el-option v-for="u in userStore.users" :key="u.id" :label="u.name" :value="u.id" />
      </el-select>
    </div>
  </el-header>
</template>

<script setup>
import { useUserStore } from '../stores/userStore.js'

const userStore = useUserStore()
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
</style>

<template>
  <div class="account-list">
    <h3>账户</h3>
    <div v-if="loading" v-loading="loading" style="height: 60px"></div>
    <el-row v-else :gutter="12">
      <el-col v-for="account in accounts" :key="account.id" :span="8">
        <el-card shadow="hover" class="account-card">
          <div class="card-top">
            <el-tag :type="tagType(account.type)" size="small">{{ typeLabel(account.type) }}</el-tag>
            <span class="account-name">{{ account.name }}</span>
          </div>
          <div class="balance" :class="{ negative: account.balance < 0 }">
            ¥{{ account.balance.toFixed(2) }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { userStore } from '../stores/userStore.js'

const accounts = ref([])
const loading = ref(true)

const typeLabel = (t) => ({ CASH: '现金', BANK: '储蓄', CARD: '信用' }[t] || t)
const tagType = (t) => ({ CASH: 'success', BANK: 'primary', CARD: 'warning' }[t] || 'info')

async function fetchAccounts() {
  loading.value = true
  try {
    const res = await fetch(`/api/accounts?userId=${userStore.currentUser}`)
    accounts.value = await res.json()
  } finally {
    loading.value = false
  }
}

onMounted(fetchAccounts)
watch(() => userStore.currentUser, fetchAccounts)
</script>

<style scoped>
.account-list { margin-bottom: 20px; }
.account-list h3 { margin-bottom: 12px; }
.card-top { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.account-name { font-weight: 600; }
.balance { font-size: 1.4rem; font-weight: 700; color: var(--el-color-success); }
.balance.negative { color: var(--el-color-danger); }
</style>

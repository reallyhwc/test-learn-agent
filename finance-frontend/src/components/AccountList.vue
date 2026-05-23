<template>
  <div class="account-list">
    <h3>账户</h3>
    <div v-if="loading">加载中...</div>
    <div v-else class="accounts">
      <div v-for="account in accounts" :key="account.id" class="account-card">
        <span class="account-type">{{ typeLabel(account.type) }}</span>
        <span class="account-name">{{ account.name }}</span>
        <span class="account-balance" :class="{ negative: account.balance < 0 }">
          ¥{{ account.balance.toFixed(2) }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const accounts = ref([])
const loading = ref(true)

const API_BASE = 'http://localhost:8080'

const typeLabel = (t) => ({ CASH: '现金', BANK: '储蓄', CARD: '信用' }[t] || t)

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/accounts`)
    accounts.value = await res.json()
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.accounts { display: flex; gap: 12px; flex-wrap: wrap; }
.account-card {
  flex: 1; min-width: 180px; padding: 16px;
  background: #f5f5f5; border-radius: 8px;
  display: flex; flex-direction: column; gap: 4px;
}
.account-type { font-size: 0.8rem; color: #888; }
.account-name { font-weight: 600; }
.account-balance { font-size: 1.2rem; font-weight: 700; color: #2ecc71; }
.account-balance.negative { color: #e74c3c; }
</style>

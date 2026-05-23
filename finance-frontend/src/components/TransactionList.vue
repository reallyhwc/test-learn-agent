<template>
  <div class="tx-list">
    <h3>交易记录</h3>
    <div class="filters">
      <input v-model="filterDate" type="date" @change="fetchList" />
      <select v-model="filterCategory" @change="fetchList">
        <option value="">全部分类</option>
        <option v-for="c in categories" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
    </div>
    <div v-if="loading">加载中...</div>
    <table v-else>
      <thead>
        <tr><th>日期</th><th>分类</th><th>类型</th><th>金额</th><th>备注</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in transactions" :key="t.id">
          <td>{{ t.date }}</td>
          <td>{{ t.category }}</td>
          <td :class="t.type">{{ t.type === 'INCOME' ? '收入' : '支出' }}</td>
          <td :class="t.type">¥{{ t.amount.toFixed(2) }}</td>
          <td>{{ t.note }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const API_BASE = 'http://localhost:8080'
const transactions = ref([])
const categories = ref([])
const loading = ref(true)
const filterDate = ref('')
const filterCategory = ref('')

onMounted(async () => {
  const [txRes, cRes] = await Promise.all([
    fetch(`${API_BASE}/api/transactions`),
    fetch(`${API_BASE}/api/categories`)
  ])
  transactions.value = await txRes.json()
  categories.value = await cRes.json()
  loading.value = false
})

async function fetchList() {
  loading.value = true
  let url = `${API_BASE}/api/transactions?`
  if (filterDate.value) url += `date=${filterDate.value}&`
  if (filterCategory.value) url += `category=${filterCategory.value}&`
  const res = await fetch(url)
  transactions.value = await res.json()
  loading.value = false
}

defineExpose({ fetchList })
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 12px; }
.filters input, .filters select { padding: 6px; border: 1px solid #ddd; border-radius: 4px; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 8px 12px; border-bottom: 1px solid #eee; text-align: left; }
.INCOME { color: #2ecc71; }
.EXPENSE { color: #e74c3c; }
</style>

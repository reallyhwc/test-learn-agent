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
    <template v-else>
      <table>
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
      <div class="pagination">
        <button :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
        <span>第 {{ page }} / {{ totalPages }} 页 (共 {{ total }} 条)</span>
        <button :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { userStore } from '../stores/userStore.js'

const API_BASE = 'http://localhost:8080'
const transactions = ref([])
const categories = ref([])
const loading = ref(true)
const filterDate = ref('')
const filterCategory = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const totalPages = ref(0)

onMounted(async () => {
  const cRes = await fetch(`${API_BASE}/api/categories`)
  categories.value = await cRes.json()
  await fetchList()
})

watch(() => userStore.currentUser, () => { page.value = 1; fetchList() })

async function fetchList() {
  loading.value = true
  let url = `${API_BASE}/api/transactions?userId=${userStore.currentUser}&page=${page.value}&pageSize=${pageSize.value}&`
  if (filterDate.value) url += `date=${filterDate.value}&`
  if (filterCategory.value) url += `category=${filterCategory.value}&`
  const res = await fetch(url)
  const data = await res.json()
  transactions.value = data.items
  total.value = data.total
  totalPages.value = data.totalPages
  loading.value = false
}

function goPage(p) {
  page.value = p
  fetchList()
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
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; }
.pagination button { padding: 6px 12px; border: 1px solid #ddd; border-radius: 4px; background: #fff; cursor: pointer; }
.pagination button:disabled { color: #ccc; cursor: default; }
.pagination span { font-size: 0.85rem; color: #666; }
</style>

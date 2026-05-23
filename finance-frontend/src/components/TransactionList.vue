<template>
  <div class="tx-list">
    <h3>交易记录</h3>
    <div class="filters">
      <el-date-picker v-model="filterDate" type="date" placeholder="选择日期" size="small" clearable
        value-format="YYYY-MM-DD" @change="page=1;fetchList()" style="width: 150px" />
      <el-select v-model="filterCategory" placeholder="全部分类" size="small" clearable
        @change="page=1;fetchList()" style="width: 130px">
        <el-option v-for="c in categories" :key="c.name" :label="c.name" :value="c.name" />
      </el-select>
    </div>
    <el-table :data="transactions" v-loading="loading" stripe size="small" style="width: 100%">
      <el-table-column prop="date" label="日期" width="110" />
      <el-table-column prop="category" label="分类" width="80" />
      <el-table-column label="类型" width="70">
        <template #default="{ row }">
          <el-tag :type="row.type === 'INCOME' ? 'success' : 'danger'" size="small">
            {{ row.type === 'INCOME' ? '收入' : '支出' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="120">
        <template #default="{ row }">
          <span :style="{ color: row.type === 'INCOME' ? 'var(--el-color-success)' : 'var(--el-color-danger)' }">
            ¥{{ row.amount.toFixed(2) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="备注" />
    </el-table>
    <div class="pagination">
      <el-pagination background layout="prev, pager, next, total" :total="total"
        v-model:page-size="pageSize" v-model:current-page="page" :page-sizes="[10, 20, 50]"
        @current-change="fetchList" @size-change="page=1;fetchList()" small />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { userStore } from '../stores/userStore.js'

const transactions = ref([])
const categories = ref([])
const loading = ref(true)
const filterDate = ref('')
const filterCategory = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(async () => {
  const cRes = await fetch(`/api/categories`)
  categories.value = await cRes.json()
  await fetchList()
})

watch(() => userStore.currentUser, () => { page.value = 1; fetchList() })

async function fetchList() {
  loading.value = true
  let url = `/api/transactions?userId=${userStore.currentUser}&page=${page.value}&pageSize=${pageSize.value}&`
  if (filterDate.value) url += `date=${filterDate.value}&`
  if (filterCategory.value) url += `category=${filterCategory.value}&`
  const res = await fetch(url)
  const data = await res.json()
  transactions.value = data.items
  total.value = data.total
  loading.value = false
}

defineExpose({ fetchList })
</script>

<style scoped>
.tx-list { margin-bottom: 20px; }
.tx-list h3 { margin-bottom: 12px; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; }
.pagination { margin-top: 12px; display: flex; justify-content: center; }
</style>

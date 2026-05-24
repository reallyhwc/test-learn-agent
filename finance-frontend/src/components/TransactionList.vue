<template>
  <div class="tx-list" role="region" aria-label="交易记录">
    <h3>交易记录</h3>
    <div class="filters">
      <el-date-picker v-model="filterDate" type="date" placeholder="选择日期" size="small" clearable
        value-format="YYYY-MM-DD" @change="page=1;fetchList()" style="width: 150px" />
      <el-select v-model="filterCategory" placeholder="全部分类" size="small" clearable
        @change="page=1;fetchList()" style="width: 130px">
        <el-option v-for="c in categories" :key="c.name" :label="c.name" :value="c.name" />
      </el-select>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 12px" />
    <el-table v-if="!error" :data="transactions" v-loading="loading" stripe size="small" style="width: 100%">
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
    <el-empty v-if="!loading && !error && transactions.length === 0" description="暂无交易记录" />
    <div class="pagination">
      <el-pagination background layout="prev, pager, next, total" :total="total"
        v-model:page-size="pageSize" v-model:current-page="page" :page-sizes="[10, 20, 50]"
        @current-change="fetchList" @size-change="page=1;fetchList()" small />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useUserStore } from '../stores/userStore.js'
import { apiGet, handleApiError } from '../utils/api.js'

const userStore = useUserStore()

const transactions = ref([])
const categories = ref([])
const loading = ref(true)
const error = ref(null)
const filterDate = ref('')
const filterCategory = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(async () => {
  try {
    categories.value = await apiGet('/api/categories')
  } catch (e) {
    handleApiError(e, '加载分类失败')
  }
  await fetchList()
})

watch(() => userStore.currentUser, () => { page.value = 1; fetchList() })

async function fetchList() {
  loading.value = true
  error.value = null
  try {
    const params = new URLSearchParams({
      userId: userStore.currentUser,
      page: page.value,
      pageSize: pageSize.value,
    })
    if (filterDate.value) params.set('date', filterDate.value)
    if (filterCategory.value) params.set('category', filterCategory.value)
    const data = await apiGet(`/api/transactions?${params}`)
    transactions.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e.message
    handleApiError(e, '加载交易记录失败')
  } finally {
    loading.value = false
  }
}

defineExpose({ fetchList })
</script>

<style scoped>
.tx-list { margin-bottom: 20px; }
.tx-list h3 { margin-bottom: 12px; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; }
.pagination { margin-top: 12px; display: flex; justify-content: center; }
</style>

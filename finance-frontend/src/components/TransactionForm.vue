<template>
  <div class="tx-form">
    <h3>记一笔</h3>
    <el-form :inline="true" @submit.prevent="submit">
      <el-form-item>
        <el-select v-model="form.accountId" placeholder="选择账户" style="width: 140px">
          <el-option v-for="a in accounts" :key="a.id" :label="a.name" :value="a.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="form.type" placeholder="类型" style="width: 100px" @change="onTypeChange">
          <el-option label="收入" value="INCOME" />
          <el-option label="支出" value="EXPENSE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input-number v-model="form.amount" :min="0.01" :precision="2" placeholder="金额" style="width: 130px" />
      </el-form-item>
      <el-form-item>
        <el-cascader
          v-model="categorySelection"
          :options="cascaderOptions"
          :props="{ expandTrigger: 'hover' }"
          placeholder="选择分类"
          style="width: 180px"
          clearable
        />
      </el-form-item>
      <el-form-item>
        <el-input v-model="form.note" placeholder="备注" style="width: 140px" />
      </el-form-item>
      <el-form-item>
        <el-button type="success" native-type="submit" :loading="submitting">保存</el-button>
      </el-form-item>
    </el-form>
    <p v-if="msg" class="msg">{{ msg }}</p>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useUserStore } from '../stores/userStore.js'
import { apiGet, apiPost, handleApiError } from '../utils/api.js'

const userStore = useUserStore()

const emit = defineEmits(['saved'])
const accounts = ref([])
const categoryTree = ref([])
const submitting = ref(false)
const msg = ref('')
const categorySelection = ref([])

const form = reactive({ accountId: '', type: '', amount: null, note: '' })

/** 根据交易类型过滤分类树，转为 el-cascader 所需的 options 格式 */
const cascaderOptions = computed(() => {
  const typeFilter = form.type === 'INCOME' ? 'INCOME' : 'EXPENSE'
  return categoryTree.value
    .filter(c => c.type === typeFilter)
    .map(c => ({
      value: c.name,
      label: c.name,
      children: (c.children || []).map(sub => ({
        value: sub.name,
        label: sub.name,
      })),
    }))
})

function onTypeChange() {
  categorySelection.value = []
}

async function fetchAccounts() {
  try {
    accounts.value = await apiGet(`/api/accounts?userId=${encodeURIComponent(userStore.currentUser)}`)
  } catch (e) {
    handleApiError(e, '加载账户失败')
  }
}

onMounted(async () => {
  try {
    categoryTree.value = await apiGet('/api/categories')
  } catch (e) {
    handleApiError(e, '加载分类失败')
  }
  await fetchAccounts()
})

watch(() => userStore.currentUser, fetchAccounts)

async function submit() {
  if (!form.accountId || !form.type || !form.amount || categorySelection.value.length < 2) return
  submitting.value = true
  msg.value = ''
  try {
    await apiPost('/api/transactions', {
      accountId: form.accountId,
      type: form.type,
      amount: form.amount,
      category: categorySelection.value[0],
      subCategory: categorySelection.value[1],
      note: form.note,
      userId: userStore.currentUser,
      date: new Date().toISOString().split('T')[0],
    })
    msg.value = '保存成功'
    form.amount = null
    form.note = ''
    categorySelection.value = []
    emit('saved')
  } catch (e) {
    msg.value = ''
    handleApiError(e, '保存失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tx-form { margin-bottom: 20px; }
.tx-form h3 { margin-bottom: 12px; }
.msg { color: var(--el-color-success); font-size: 0.85rem; }
</style>

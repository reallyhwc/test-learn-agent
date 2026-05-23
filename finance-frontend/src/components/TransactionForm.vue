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
        <el-select v-model="form.type" placeholder="类型" style="width: 100px">
          <el-option label="收入" value="INCOME" />
          <el-option label="支出" value="EXPENSE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-input-number v-model="form.amount" :min="0.01" :precision="2" placeholder="金额" style="width: 130px" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="form.category" placeholder="分类" style="width: 110px">
          <el-option v-for="c in categories" :key="c.name" :label="c.name" :value="c.name" />
        </el-select>
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
import { ref, reactive, onMounted, watch } from 'vue'
import { userStore } from '../stores/userStore.js'

const emit = defineEmits(['saved'])
const accounts = ref([])
const categories = ref([])
const submitting = ref(false)
const msg = ref('')

const form = reactive({ accountId: '', type: '', amount: null, category: '', note: '' })

async function fetchAccounts() {
  const res = await fetch(`/api/accounts?userId=${userStore.currentUser}`)
  accounts.value = await res.json()
}

onMounted(async () => {
  const cRes = await fetch(`/api/categories`)
  categories.value = await cRes.json()
  await fetchAccounts()
})

watch(() => userStore.currentUser, fetchAccounts)

async function submit() {
  if (!form.accountId || !form.type || !form.amount || !form.category) return
  submitting.value = true
  msg.value = ''
  try {
    const res = await fetch(`/api/transactions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...form,
        userId: userStore.currentUser,
        date: new Date().toISOString().split('T')[0]
      })
    })
    if (res.ok) {
      msg.value = '保存成功'
      form.amount = null; form.note = ''
      emit('saved')
    }
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

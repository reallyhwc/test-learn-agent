<template>
  <div class="tx-form">
    <h3>记一笔</h3>
    <form @submit.prevent="submit">
      <select v-model="form.accountId" required>
        <option value="">选择账户</option>
        <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>
      <select v-model="form.type" required>
        <option value="">类型</option>
        <option value="INCOME">收入</option>
        <option value="EXPENSE">支出</option>
      </select>
      <input v-model.number="form.amount" type="number" step="0.01" placeholder="金额" required />
      <select v-model="form.category" required>
        <option value="">分类</option>
        <option v-for="c in categories" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <input v-model="form.note" placeholder="备注（可选）" />
      <button type="submit" :disabled="submitting">保存</button>
    </form>
    <p v-if="msg" class="msg">{{ msg }}</p>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { userStore } from '../stores/userStore.js'

const emit = defineEmits(['saved'])
const API_BASE = 'http://localhost:8080'
const accounts = ref([])
const categories = ref([])
const submitting = ref(false)
const msg = ref('')

const form = reactive({
  accountId: '', type: '', amount: null, category: '', note: ''
})

async function fetchAccounts() {
  const res = await fetch(`${API_BASE}/api/accounts?userId=${userStore.currentUser}`)
  accounts.value = await res.json()
}

onMounted(async () => {
  const cRes = await fetch(`${API_BASE}/api/categories`)
  categories.value = await cRes.json()
  await fetchAccounts()
})

watch(() => userStore.currentUser, fetchAccounts)

async function submit() {
  submitting.value = true
  msg.value = ''
  try {
    const res = await fetch(`${API_BASE}/api/transactions`, {
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
      form.amount = null; form.note = ''; form.category = ''
      emit('saved')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tx-form { margin-bottom: 20px; }
form { display: flex; gap: 8px; flex-wrap: wrap; }
select, input, button { padding: 8px; border: 1px solid #ddd; border-radius: 4px; }
button { background: #2ecc71; color: #fff; border: none; cursor: pointer; }
button:disabled { background: #95a5a6; }
.msg { color: #2ecc71; font-size: 0.85rem; }
</style>

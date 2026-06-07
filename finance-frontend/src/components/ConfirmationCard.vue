<template>
  <div class="confirmation-card" role="alert">
    <div class="card-header">
      <span class="card-icon">&#x26A0;&#xFE0F;</span>
      <span>操作确认</span>
      <span class="countdown">{{ remaining }}s 后自动取消</span>
    </div>
    <div class="card-body">
      <p class="desc">{{ description }}</p>
      <div class="params">
        <div v-for="(v, k) in displayParams" :key="k" class="param-row">
          <span class="param-key">{{ paramLabel(k) }}</span>
          <span class="param-value">{{ formatValue(k, v) }}</span>
        </div>
      </div>
    </div>
    <div class="card-actions">
      <el-button type="primary" @click="$emit('confirm')">确认执行</el-button>
      <el-button @click="$emit('cancel')">取消</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  confirmationId: { type: String, required: true },
  toolName: { type: String, required: true },
  description: { type: String, default: '' },
  parameters: { type: Object, default: () => ({}) },
  expiresAt: { type: String, default: '' },
})

defineEmits(['confirm', 'cancel'])

const remaining = ref(60)
let timer = null

onMounted(() => {
  if (props.expiresAt) {
    const expires = new Date(props.expiresAt).getTime()
    timer = setInterval(() => {
      remaining.value = Math.max(0, Math.round((expires - Date.now()) / 1000))
      if (remaining.value <= 0) {
        clearInterval(timer)
      }
    }, 1000)
  }
})

onUnmounted(() => { if (timer) clearInterval(timer) })

const paramLabels = {
  amount: '金额', type: '类型', category: '一级分类',
  subCategory: '二级分类', note: '备注', accountId: '账户'
}

function paramLabel(key) { return paramLabels[key] || key }

function formatValue(key, val) {
  if (key === 'amount') return '¥' + Number(val).toFixed(2)
  if (key === 'type') return val === 'EXPENSE' ? '支出' : val === 'INCOME' ? '收入' : String(val)
  return String(val)
}

const displayParams = computed(() => {
  const { confirmationId, toolName, description, expiresAt, ...rest } = props.parameters || {}
  return rest
})
</script>

<style scoped>
.confirmation-card {
  border: 1px solid #e6a23c;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  background: #fdf6ec;
}
.card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-weight: 600; }
.countdown { margin-left: auto; font-size: 12px; color: #909399; }
.card-body { margin-bottom: 12px; }
.desc { margin: 0 0 8px 0; color: #606266; }
.params { background: #fff; border-radius: 4px; padding: 8px; }
.param-row { display: flex; justify-content: space-between; padding: 4px 0; }
.param-key { color: #909399; font-size: 13px; }
.param-value { font-weight: 500; }
.card-actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>

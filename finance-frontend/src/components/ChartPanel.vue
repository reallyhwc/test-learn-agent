<template>
  <div class="chart-panel" v-if="hasData">
    <h3>统计图表</h3>
    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>日收支曲线</template>
          <v-chart :option="lineOption" autoresize style="height: 300px" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>支出分类占比</template>
          <v-chart :option="pieOption" autoresize style="height: 300px" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { useUserStore } from '../stores/userStore.js'
import { apiGet } from '../utils/api.js'

use([CanvasRenderer, LineChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent])

const userStore = useUserStore()

const chartData = ref([])
const hasData = computed(() => chartData.value.length > 0)

async function fetchData() {
  try {
    const data = await apiGet(`/api/transactions?userId=${encodeURIComponent(userStore.currentUser)}&pageSize=1000`)
    chartData.value = data.items || []
  } catch (e) {
    chartData.value = []
  }
}

onMounted(fetchData)
watch(() => userStore.currentUser, fetchData)

// Daily income/expense curve
const lineOption = computed(() => {
  const dailyMap = {}
  chartData.value.forEach(t => {
    if (!dailyMap[t.date]) dailyMap[t.date] = { income: 0, expense: 0 }
    if (t.type === 'INCOME') dailyMap[t.date].income += t.amount
    else dailyMap[t.date].expense += t.amount
  })
  const dates = Object.keys(dailyMap).sort()
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['收入', '支出'], bottom: 0 },
    grid: { left: '3%', right: '4%', bottom: '15%', containLabel: true },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value' },
    series: [
      { name: '收入', type: 'line', data: dates.map(d => dailyMap[d].income),
        smooth: true, color: '#2ecc71' },
      { name: '支出', type: 'line', data: dates.map(d => dailyMap[d].expense),
        smooth: true, color: '#e74c3c' }
    ]
  }
})

// Category pie chart (expenses only)
const pieOption = computed(() => {
  const catMap = {}
  chartData.value.filter(t => t.type === 'EXPENSE').forEach(t => {
    catMap[t.category] = (catMap[t.category] || 0) + t.amount
  })
  const data = Object.entries(catMap).map(([name, value]) => ({ name, value }))
  return {
    tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie', radius: ['40%', '70%'], center: ['50%', '45%'],
      data, label: { formatter: '{b}\n{d}%' },
      emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' } }
    }]
  }
})
</script>

<style scoped>
.chart-panel { margin-bottom: 20px; }
.chart-panel h3 { margin-bottom: 12px; }
</style>

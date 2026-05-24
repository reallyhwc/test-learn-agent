<template>
  <div class="chart-wrapper">
    <div v-if="error" class="chart-error">
      <span>图表加载失败</span>
      <pre>{{ rawData }}</pre>
    </div>
    <v-chart v-else :option="option" autoresize class="chart-instance" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, PieChart, LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, PieChart, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

const props = defineProps({
  type: { type: String, required: true },
  title: { type: String, default: '' },
  headers: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] }
})

const error = ref(false)

const rawData = computed(() => {
  return [props.headers.join(',')].concat(props.rows.map(r => r.join(','))).join('\n')
})

const COLORS = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4']

const option = computed(() => {
  try {
    const base = {
      title: { text: props.title, left: 'center', textStyle: { fontSize: 14 } },
      tooltip: { trigger: 'axis' },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true }
    }

    if (props.type === 'pie') {
      const data = props.rows.map(r => ({ name: r[0], value: parseFloat(r[1]) || 0 }))
      return {
        title: base.title,
        tooltip: { trigger: 'item' },
        series: [{
          type: 'pie', radius: ['40%', '70%'], center: ['50%', '55%'],
          data, label: { formatter: '{b}: {d}%' },
          itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 }
        }],
        color: COLORS
      }
    }

    const legend = props.headers.length > 2 ? { legend: { data: props.headers.slice(1) } } : {}
    const xData = props.rows.map(r => r[0])
    const series = props.headers.slice(1).map((name, i) => ({
      name,
      type: props.type,
      data: props.rows.map(r => parseFloat(r[i + 1]) || 0),
      itemStyle: { borderRadius: props.type === 'bar' ? [4, 4, 0, 0] : undefined }
    }))

    return {
      ...base,
      ...legend,
      xAxis: { type: 'category', data: xData },
      yAxis: { type: 'value' },
      series,
      color: COLORS
    }
  } catch (e) {
    error.value = true
    return {}
  }
})
</script>

<style scoped>
.chart-wrapper { margin: 12px 0; }
.chart-instance { width: 100%; height: 280px; }
.chart-error {
  padding: 16px; background: #fef0f0; border-radius: 8px; color: #f56c6c; font-size: 13px;
}
.chart-error pre {
  margin: 8px 0 0; padding: 8px; background: #fff; border-radius: 4px; font-size: 12px;
  color: #666; white-space: pre-wrap; word-break: break-all;
}
</style>

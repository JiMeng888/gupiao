<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'

/**
 * ECharts 容器。
 *
 * 存在的意义是把「init / setOption / resize / dispose」这套生命周期
 * 收在一处。散落在各个视图里手写的话，最容易漏掉的是两件事：
 *   1. 组件卸载时 dispose —— 漏了会积累游离的 canvas 与事件监听，
 *      在行情页反复切换标的时会明显感到卡顿；
 *   2. 容器尺寸变化时 resize —— 侧边栏收起、窗口拉伸时图表会被拉花。
 *
 * 用 ResizeObserver 而不是 window.resize：图表所在容器可能因为
 * 布局变化（如抽屉展开）而改变宽度，但不触发 window 的 resize。
 */
const props = withDefaults(
  defineProps<{
    option: EChartsOption
    height?: string | number
    loading?: boolean
    /** 数据为空时展示的文案 */
    emptyText?: string
    isEmpty?: boolean
  }>(),
  {
    height: 320,
    loading: false,
    emptyText: '暂无数据',
    isEmpty: false,
  },
)

const container = ref<HTMLDivElement | null>(null)
// shallowRef：ECharts 实例内部结构庞大，做成深响应式会带来明显的性能损耗
const chart = shallowRef<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null

const heightStyle = typeof props.height === 'number' ? `${props.height}px` : props.height

function render(): void {
  if (!chart.value) return
  // notMerge: true —— 切换标的时系列数量可能变化（如从 4 个指标切到 1 个），
  // 合并模式会残留上一次的系列，表现为图例里多出不该有的项
  chart.value.setOption(props.option, { notMerge: true })
}

function init(): void {
  if (!container.value || chart.value) return
  chart.value = echarts.init(container.value)
  render()

  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(container.value)
}

onMounted(init)

watch(() => props.option, render, { deep: true })

watch(
  () => props.loading,
  (v) => {
    if (!chart.value) return
    if (v) chart.value.showLoading('default', { text: '加载中', maskColor: 'rgba(255,255,255,0.6)' })
    else chart.value.hideLoading()
  },
)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})

defineExpose({
  /** 需要把图表导出为图片时用（如「保存为 PNG」按钮） */
  getInstance: () => chart.value,
})
</script>

<template>
  <div class="chart-wrapper" :style="{ height: heightStyle }">
    <div v-show="!isEmpty" ref="container" class="chart-canvas" />
    <div v-if="isEmpty" class="chart-empty">
      <el-empty :description="emptyText" :image-size="72" />
    </div>
  </div>
</template>

<style scoped>
.chart-wrapper {
  position: relative;
  width: 100%;
}

.chart-canvas {
  width: 100%;
  height: 100%;
}

.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
</style>

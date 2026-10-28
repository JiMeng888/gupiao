<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
// 从统一入口拿已注册好模块的 echarts（见 charts/echarts.ts 的说明）
import { echarts } from '@/charts/echarts'
// 仅类型导入，编译后被完全擦除，不会把全量包带回来。
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

/**
 * ECharts 的 loading 遮罩与文字不吃 CSS 变量，只能取出当前主题的值再塞进去。
 *
 * <p>写死 {@code rgba(255,255,255,0.6)} 的后果是：深色页面上加载图表时
 * 会闪出一整块白蒙层，比不加遮罩还刺眼。文字同理——默认是黑色，
 * 压在深色蒙层上等于隐身。
 */
function chartLoadingStyle(): {
  text: string
  color: string
  textColor: string
  maskColor: string
} {
  if (typeof window === 'undefined') {
    return { text: '加载中', color: '#409eff', textColor: '#4e5969', maskColor: 'rgba(255,255,255,0.65)' }
  }
  const styles = getComputedStyle(document.documentElement)
  const read = (name: string, fallback: string): string =>
    styles.getPropertyValue(name).trim() || fallback
  return {
    text: '加载中',
    // 转圈用主色，文字用页面当前的主题文本色
    color: read('--el-color-primary', '#409eff'),
    textColor: read('--chart-text', '#4e5969'),
    maskColor: read('--chart-loading-mask', 'rgba(255,255,255,0.65)'),
  }
}

watch(
  () => props.loading,
  (v) => {
    if (!chart.value) return
    if (v) chart.value.showLoading('default', chartLoadingStyle())
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

import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 把 CSS 变量里的涨跌色取出来给 ECharts 用。
 *
 * <h2>解决的问题</h2>
 * 任务书里有一条硬要求：<b>涨跌色必须走主题 token，不能写死在组件里</b>。
 * 难点在于 ECharts 的 option 是<b>纯 JS 对象</b>，它处在 CSS 之外——
 * 你没法在 option 里写 {@code color: var(--color-up)}。
 * 结果就是实际工程里几乎人人都在组件里写
 * {@code const UP = '#d92b2b'}，然后暗色主题一上，颜色就脱节了。
 *
 * <p>这个 composable 的做法是：从 {@code <html>} 的计算样式里把变量值读出来，
 * 交给 Vue 的响应式系统；主题一换，值跟着变，图表 option 重算，
 * 于是「涨跌色只有一处定义」这条约束能真正成立。
 *
 * <h2>为什么监听的是 data-theme 变化而不是 window 事件</h2>
 * 主题切换是本应用内部的主动行为，用 MutationObserver 盯住
 * {@code <html>} 的 {@code data-theme} 属性最可靠：
 * 无论从哪个入口切（顶栏按钮、设置页、系统 prefers-color-scheme 跟随），
 * 都不需要每个调用处记得发一个事件。
 */

/** 图表需要的所有主题色。 */
export interface ChartTheme {
  up: string
  down: string
  flat: string
  axis: string
  split: string
  text: string
  tooltipBg: string
  tooltipBorder: string
  /** 十字光标坐标标签底色 */
  crossLabel: string
  /** 均线 / MACD 等叠加线配色，与涨跌色区分开以免误读 */
  series: {
    ma5: string
    ma10: string
    ma20: string
    ma60: string
    dif: string
    dea: string
    rsi: string
    price: string
  }
}

/** 变量缺失时的兜底值。宁可显示成灰色，也不要渲染出透明或黑色。 */
const FALLBACK: ChartTheme = {
  up: '#d92b2b',
  down: '#0a9e5c',
  flat: '#8c8c8c',
  axis: '#dcdfe6',
  split: '#f0f2f5',
  text: '#4e5969',
  tooltipBg: 'rgba(255,255,255,0.96)',
  tooltipBorder: '#e4e7ed',
  crossLabel: '#606266',
  series: {
    ma5: '#e6a23c',
    ma10: '#409eff',
    ma20: '#9b59b6',
    ma60: '#909399',
    dif: '#e6a23c',
    dea: '#409eff',
    rsi: '#9b59b6',
    price: '#f0a020',
  },
}

const VAR_NAMES: Record<'up' | 'down' | 'flat' | 'axis' | 'split' | 'text' | 'tooltipBg' | 'tooltipBorder' | 'crossLabel', string> = {
  up: '--chart-up',
  down: '--chart-down',
  flat: '--color-flat',
  axis: '--chart-axis',
  split: '--chart-split',
  text: '--chart-text',
  tooltipBg: '--chart-tooltip-bg',
  tooltipBorder: '--chart-tooltip-border',
  crossLabel: '--chart-cross-label',
}

/** 叠加线系列色对应的 CSS 变量名。 */
const SERIES_VAR_NAMES: Record<keyof ChartTheme['series'], string> = {
  ma5: '--chart-ma5',
  ma10: '--chart-ma10',
  ma20: '--chart-ma20',
  ma60: '--chart-ma60',
  dif: '--chart-dif',
  dea: '--chart-dea',
  rsi: '--chart-rsi',
  price: '--chart-price',
}

function readVar(styles: CSSStyleDeclaration, name: string, fallback: string): string {
  const raw = styles.getPropertyValue(name).trim()
  return raw || fallback
}

function readTheme(): ChartTheme {
  if (typeof window === 'undefined') {
    return FALLBACK
  }
  const styles = getComputedStyle(document.documentElement)
  const result: ChartTheme = {
    ...FALLBACK,
    series: { ...FALLBACK.series },
  }
  for (const key of Object.keys(VAR_NAMES) as Array<keyof typeof VAR_NAMES>) {
    result[key] = readVar(styles, VAR_NAMES[key], FALLBACK[key])
  }
  for (const key of Object.keys(SERIES_VAR_NAMES) as Array<keyof typeof SERIES_VAR_NAMES>) {
    result.series[key] = readVar(
      styles,
      SERIES_VAR_NAMES[key],
      FALLBACK.series[key],
    )
  }
  return result
}

/** 图表主题色。值随 `<html data-theme>` 变化而更新。 */
export function useChartTheme(): Ref<ChartTheme> {
  const theme = ref<ChartTheme>(readTheme())
  let observer: MutationObserver | null = null

  onMounted(() => {
    observer = new MutationObserver(() => {
      theme.value = readTheme()
    })
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    })
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })

  return theme
}

/**
 * 按数值返回涨跌色。
 *
 * <p>与 {@code ChangeText} 组件共用同一套判定（正数涨、负数跌、零持平），
 * 避免「表格里是红的、图上却是绿的」这种只有合起来看才发现的不一致。
 */
export function colorOf(value: number | null | undefined, theme: ChartTheme): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return theme.flat
  }
  if (value > 0) return theme.up
  if (value < 0) return theme.down
  return theme.flat
}

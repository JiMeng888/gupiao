<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { echarts } from '@/charts/echarts'
// 仅类型导入，编译后被完全擦除，不会把全量包带回来。
import type { EChartsOption } from 'echarts'
import type { Bar, IndicatorResponse } from '@/api/types'
import { quoteApi } from '@/api/endpoints'
import { useChartTheme, colorOf, type ChartTheme } from '@/composables/useChartTheme'
import { track } from '@/utils/track'
import { formatVolume } from '@/utils/format'

/**
 * 个股行情图（任务书 M2）：单实例、多 grid 的专业行情图。
 *
 * <h2>为什么必须是「一个 ECharts 实例 + 多个 grid」</h2>
 * 任务书要求主图（K 线）与副图（成交量）共享十字光标
 * （{@code axisPointer.link.xAxisIndex='all'}）。
 * <b>这条联动只在同一个实例内成立</b>——跨实例的 axisPointer 无法互相感知。
 * 之前是主图、副图两个独立的 {@code <BaseChart>}，光标只在各自图内出现，
 * 看上去是「两幅图叠在一起」，却无法对照同一时刻的量价关系，
 * 而这恰恰是看盘最核心的动作。
 *
 * <h2>阳线空心</h2>
 * A 股习惯是「空心红涨、实心绿跌」。ECharts 默认阳线为实心红，
 * 这里把 {@code itemStyle.color} 设为透明、只保留红色边框；
 * 阴线保持实心填充，便于快速扫读。
 *
 * <h2>dataZoom 的两个约束</h2>
 * <ul>
 *   <li>{@code minValueSpan: 30}——最少显示 30 根，防止一路放大到只剩
 *       两三根 K 线（此时趋势完全不可读）；</li>
 *   <li>{@code zoomLock: true}——锁定缩放，只平移窗口。
 *       配合「初始只显示末尾约 120 根」，避免一次渲染上万根 K 线把页面卡死。</li>
 * </ul>
 */
const props = withDefaults(
  defineProps<{
    code: string
    /** 周期别名：trend | 5d | daily | weekly | monthly | quarterly | yearly */
    period: string
    /** 技术指标数据，由父组件加载后传入 */
    indicators?: IndicatorResponse | null
    /** 图表高度（px） */
    height?: number
  }>(),
  {
    indicators: null,
    height: 420,
  },
)

const emit = defineEmits<{
  (e: 'update:period', period: string): void
}>()

type IndicatorKey = 'MACD' | 'RSI' | 'BOLL' | 'KDJ'

/** 各周期的取数口径。 */
interface PeriodSpec {
  /** 发给后端的 period 别名 */
  upstream: string
  /** 请求根数 */
  limit: number
  /** 是否走分时形态（折线 + 双 Y 轴） */
  intraday: boolean
  /** 口径说明，直接展示给用户，避免误读 */
  hint: string
}

/**
 * 「5 日」这一档没有独立数据源。
 *
 * <p>富途那类产品里「5日」是连续 5 个交易日的分时走势，需要专门的分时端点。
 * 本项目后端没有该端点，因此用近 5 个交易日的<b>日线收盘</b>表达，
 * 并把口径原样写在界面上——宁可说明白，也不要拿别的数据冒充。
 */
const PERIOD_SPECS: Record<string, PeriodSpec> = {
  trend: { upstream: 'trend', limit: 241, intraday: true, hint: '当日逐分钟走势，左轴价格 / 右轴涨跌幅' },
  '5d': { upstream: 'daily', limit: 5, intraday: true, hint: '近 5 个交易日日线收盘（无 5 日分时数据源）' },
  daily: { upstream: 'daily', limit: 180, intraday: false, hint: '日 K，叠加 MA5 / 10 / 20 / 60' },
  weekly: { upstream: 'weekly', limit: 180, intraday: false, hint: '周 K' },
  monthly: { upstream: 'monthly', limit: 180, intraday: false, hint: '月 K' },
  quarterly: { upstream: 'quarterly', limit: 120, intraday: false, hint: '季 K' },
  yearly: { upstream: 'yearly', limit: 60, intraday: false, hint: '年 K' },
}

/** 周期标签。 */
const PERIODS = [
  { label: '分时', value: 'trend' },
  { label: '5日', value: '5d' },
  { label: '日K', value: 'daily' },
  { label: '周K', value: 'weekly' },
  { label: '月K', value: 'monthly' },
  { label: '季K', value: 'quarterly' },
  { label: '年K', value: 'yearly' },
]

/** 副图指标。 */
const INDICATORS: IndicatorKey[] = ['MACD', 'RSI', 'BOLL', 'KDJ']

/** 初始窗口只显示末尾约 120 根（任务书 M2 验收项）。 */
const VISIBLE_BARS = 120

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

const bars = ref<Bar[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

/** 当前选中的副图指标。放在图表内部：指标属于图表，不该由页面代管。 */
const activeIndicator = ref<IndicatorKey>('MACD')

const container = ref<HTMLDivElement | null>(null)
// shallowRef：ECharts 实例内部结构庞大，做成深响应式会带来明显的性能损耗
const chart = shallowRef<ReturnType<typeof echarts.init> | null>(null)

let observer: ResizeObserver | null = null
/** 当前在途请求的取消控制器。 */
let inflight: AbortController | null = null

const theme = useChartTheme()

// ---------------------------------------------------------------------------
// 数据推导
// ---------------------------------------------------------------------------

const spec = computed(() => PERIOD_SPECS[props.period] ?? PERIOD_SPECS.daily)

/** x 轴标签：分钟级含时间，日线只留日期。 */
const categories = computed(() =>
  bars.value.map((b) => {
    const raw = b.date ?? ''
    return raw.length > 10 ? raw.slice(5, 16) : raw
  }),
)

/**
 * K 线数据：[开, 收, 低, 高]。
 *
 * <p><b>是 OCLH 不是 OHLC</b>——顺序写反图形会整体失真且不一定报错，
 * 属于最难发现的一类 bug，所以在这里写明并固定下来。
 */
const candleData = computed(() => bars.value.map((b) => [b.open, b.close, b.low, b.high]))

/** 分时形态下的收盘价序列。 */
const closeLine = computed(() => bars.value.map((b) => b.close))

/** 涨跌幅基准价：首根的昨收，缺昨收时退回首根开盘。 */
const pctBase = computed(() => {
  const first = bars.value[0]
  return first?.prevClose || first?.open || 0
})

/**
 * 主图价格轴的值域。
 *
 * <p>分时图要「左轴价格 + 右轴涨跌幅」双轴对齐，而 <b>ECharts 不会自动同步
 * 两个 y 轴的值域</b>——各自 scale 的话两条线会错开，十字光标也就失去对照意义。
 * 这里先定价格轴的 min/max，再把同一对端点换算成百分比交给右轴，
 * 于是两条轴在纵向上严格一一对应。
 *
 * <p>涨跌幅的基准固定取首根昨收，而不是逐根相对上一根：分时图描述的是
 * 当日相对昨收的偏离，基准一致时右轴的 0% 才与昨收价严格重合。
 */
const priceBounds = computed(() => {
  const values = closeLine.value.filter((v) => Number.isFinite(v))
  if (!values.length) return null
  const min = Math.min(...values)
  const max = Math.max(...values)
  // 一字板时 max === min，给一个最小跨度，避免轴退化成一个点
  const span = Math.max(max - min, Math.abs(max) * 0.002, 0.01)
  const pad = span * 0.08
  return { min: Number((min - pad).toFixed(4)), max: Number((max + pad).toFixed(4)) }
})

/** 价格 -> 相对基准的百分比，用于右轴端点换算。 */
function toPct(price: number): number {
  const base = pctBase.value
  if (!base) return 0
  return Number((((price - base) / base) * 100).toFixed(2))
}

/** 成交量：按「当根相对昨收」的涨跌着色——表达的是当天涨跌，不是阴阳线。 */
const volumeData = computed(() =>
  bars.value.map((b) => ({
    value: b.volume,
    itemStyle: { color: colorOf(b.close - b.prevClose, theme.value) },
  })),
)

const isEmpty = computed(() => !loading.value && !error.value && bars.value.length === 0)

/**
 * 取消上一个在途请求。
 *
 * <p><b>为什么必须显式做这件事</b>：周期切换是高频操作，各周期上游耗时不同
 * （分时通常比日 K 快）。不取消时，用户从 A 切到 B，A 的响应可能晚于 B 到达，
 * 把 B 的数据覆盖掉——画面表现为「点了 B，先闪一下 A 再跳回 B」。
 * 这类竞态只在网络抖动时出现，开发环境（局域网几乎零延迟）极难复现。
 */
function abortInFlight(): void {
  inflight?.abort()
  inflight = null
}

async function load(): Promise<void> {
  abortInFlight()
  const controller = new AbortController()
  inflight = controller

  loading.value = true
  error.value = null
  try {
    const result = await quoteApi.bars(
      props.code,
      { period: spec.value.upstream, limit: spec.value.limit },
      { signal: controller.signal },
    )
    // 已被后来者取代：结果丢弃，且不许动 loading（否则会关掉新请求的加载态）
    if (inflight !== controller) return
    bars.value = result
  } catch (e) {
    if (inflight !== controller) return
    error.value = e instanceof Error ? e.message : 'K 线加载失败'
    bars.value = []
  } finally {
    if (inflight === controller) {
      loading.value = false
      inflight = null
    }
  }
}

/**
 * 系列「草案」类型。
 *
 * <p>ECharts 的 {@code series} 是 {@code SeriesOption | SeriesOption[]} 联合类型，
 * 直接拿它当数组下标/展开会让 TS 报「可能没有迭代器」。
 * 因此内部统一用宽松的 {@code Record} 草案拼装，最后一次断言成真正的 option。
 */
type SeriesDraft = Record<string, unknown>
type SeriesList = SeriesDraft[]

/** 三个 pane 的 grid 下标常量，避免散落的魔法数字。 */
const GRID_MAIN = 0
const GRID_VOLUME = 1
const GRID_INDICATOR = 2

const ma = computed(() => props.indicators?.ma)

/**
 * 均线定义。
 *
 * <p><b>只渲染上游确实给了的那些</b>：MA60 需要至少 60 根 K 线，
 * 上市时间不足或上游只回了 30 根时它是空的。
 * 若照样塞进 option，图例里会出现一条点不掉的空线，
 * 用户会以为是「数据加载了一半」。
 *
 * <p>颜色不写死，从 {@code theme.series} 取，随主题一起变。
 */
const MA_DEFS: Array<{
  name: string
  pick: () => number[] | undefined
  color: (t: ChartTheme) => string
}> = [
  { name: 'MA5', pick: () => ma.value?.ma5, color: (t) => t.series.ma5 },
  { name: 'MA10', pick: () => ma.value?.ma10, color: (t) => t.series.ma10 },
  { name: 'MA20', pick: () => ma.value?.ma20, color: (t) => t.series.ma20 },
  { name: 'MA60', pick: () => ma.value?.ma60, color: (t) => t.series.ma60 },
]

function buildMaSeries(): SeriesList {
  const t = theme.value
  return MA_DEFS.filter((d) => {
    const arr = d.pick()
    return Array.isArray(arr) && arr.length > 0
  }).map((d) => ({
    name: d.name,
    type: 'line' as const,
    data: d.pick() as number[],
    smooth: true,
    showSymbol: false,
    lineStyle: { width: 1, color: d.color(t) },
    itemStyle: { color: d.color(t) },
  }))
}

function buildVolumeSeries(): SeriesList {
  return [
    {
      name: '成交量',
      type: 'bar' as const,
    data: volumeData.value,
      xAxisIndex: GRID_VOLUME,
      yAxisIndex: 2,
      barWidth: '62%',
    },
  ] as SeriesList
}

/** 副图指标，统一挂在 grid 2（xAxisIndex 2 / yAxisIndex 3）。 */
function buildIndicatorSeries(): SeriesList {
  const ind = props.indicators
  if (!ind) return []
  const t = theme.value
  const onPane = (s: Record<string, unknown>) => ({ ...s, xAxisIndex: GRID_INDICATOR, yAxisIndex: 3 })

  if (activeIndicator.value === 'MACD' && ind.macd) {
    return [
      onPane({
        name: 'DIF',
        type: 'line',
        data: ind.macd.dif,
        showSymbol: false,
        lineStyle: { width: 1, color: t.series.dif },
      }),
      onPane({
        name: 'DEA',
        type: 'line',
        data: ind.macd.dea,
        showSymbol: false,
        lineStyle: { width: 1, color: t.series.dea },
      }),
      {
        name: 'MACD',
        type: 'bar' as const,
        data: ind.macd.macd.map((v) => ({ value: v, itemStyle: { color: colorOf(v, t) } })),
        xAxisIndex: GRID_INDICATOR,
        yAxisIndex: 3,
        barWidth: '48%',
      },
    ] as SeriesList
  }

  if (activeIndicator.value === 'RSI' && ind.rsi?.length) {
    return [
      onPane({
        name: 'RSI',
        type: 'line',
        data: ind.rsi,
        showSymbol: false,
        lineStyle: { width: 1.4, color: t.series.rsi },
        // 30 / 70 是 RSI 超卖 / 超买的常规参考线。
        // 缺了它，单看一条 0~100 的曲线无法判断强弱
        markLine: {
          silent: true,
          symbol: 'none',
          label: { fontSize: 10, color: t.text },
        data: [
          { yAxis: 30, lineStyle: { color: t.down, type: 'dashed' as const } },
          { yAxis: 70, lineStyle: { color: t.up, type: 'dashed' as const } },
        ],
        },
      }),
    ] as SeriesList
  }

  if (activeIndicator.value === 'BOLL' && ind.boll) {
    return [
      onPane({ name: '上轨', type: 'line', data: ind.boll.upper, showSymbol: false, lineStyle: { width: 1 } }),
      onPane({ name: '中轨', type: 'line', data: ind.boll.mid, showSymbol: false, lineStyle: { width: 1.2 } }),
      onPane({ name: '下轨', type: 'line', data: ind.boll.lower, showSymbol: false, lineStyle: { width: 1 } }),
    ] as SeriesList
  }

  if (activeIndicator.value === 'KDJ' && ind.kdj) {
    return [
      onPane({ name: 'K', type: 'line', data: ind.kdj.k, showSymbol: false, lineStyle: { width: 1 } }),
      onPane({ name: 'D', type: 'line', data: ind.kdj.d, showSymbol: false, lineStyle: { width: 1 } }),
      onPane({ name: 'J', type: 'line', data: ind.kdj.j, showSymbol: false, lineStyle: { width: 1 } }),
    ] as SeriesList
  }

  return []
}

/**
 * dataZoom 起始百分比。
 *
 * <p>用「可见根数 / 总根数」换算而不是写死 60%：各周期请求根数不同
 * （日 K 要 180 根，5 日只要 5 根），写死百分比会让短周期窗口过窄、
 * 长周期又不够聚焦。总根数不足时从头显示，避免
 * 「只有 5 根却只显示 3 根」这种无意义裁剪。
 */
const zoomStart = computed(() => {
  const total = bars.value.length
  if (total <= 0) return 0
  if (total <= VISIBLE_BARS) return 0
  return Math.max(0, 100 - (VISIBLE_BARS / total) * 100)
})

/**
 * 自定义 tooltip。
 *
 * <p>为什么不用默认的：默认轴触发会把蜡烛图拆成「开 / 收 / 低 / 高」四条独立记录，
 * 而用户要看的是「这一天的开盘价 / 收盘价 / 最高 / 最低」一行。
 * 这里按 dataIndex 把主图的 OHLC 与副图的成交量合成一块。
 *
 * <p>{@code params} 的类型故意收得很窄：ECharts 的回调参数是个大联合类型，
 * 直接引用会在不同版本间漂移。只取需要的三个字段，其余不碰。
 */
interface TooltipParam {
  dataIndex?: number
  seriesName?: string
  value?: unknown
}

function tooltipFormatter(raw: unknown): string {
  const list: TooltipParam[] = Array.isArray(raw) ? (raw as TooltipParam[]) : [raw as TooltipParam]
  const bar = bars.value[list[0]?.dataIndex ?? -1]
  if (!bar) return ''

  const change = bar.close - bar.prevClose
  const sign = change >= 0 ? '+' : ''
  const pct = bar.prevClose ? (change / bar.prevClose) * 100 : 0
  // 平盘时涨跌色都没有意义，退回普通文本色
  const color = change === 0 ? theme.value.text : colorOf(change, theme.value)

  const row = (label: string, value: number) =>
    '<div>' + label + ' ' + value.toFixed(2) + '</div>'

  return (
    '<div style="font-weight:600;margin-bottom:4px">' +
    (bar.date ?? '') +
    '</div>' +
    '<div>收盘 <b style="color:' +
    color +
    '">' +
    bar.close.toFixed(2) +
    '</b> <span style="color:' +
    color +
    '">' +
    sign +
    pct.toFixed(2) +
    '%</span></div>' +
    row('开盘', bar.open) +
    '<div>最高 <span style="color:' +
    theme.value.up +
    '">' +
    bar.high.toFixed(2) +
    '</span></div>' +
    '<div>最低 <span style="color:' +
    theme.value.down +
    '">' +
    bar.low.toFixed(2) +
    '</span></div>' +
    '<div style="margin-top:4px">成交量 ' +
    formatVolume(bar.volume) +
    '</div>'
  )
}

const option = computed<EChartsOption>(() => {
  const t = theme.value
  const intraday = spec.value.intraday
  const cats = categories.value
  const bounds = priceBounds.value

  /** 主图系列：分时走折线，其余走蜡烛图（K 线 + 均线）。 */
  const mainSeries: SeriesList = intraday
    ? [
        {
          name: '价格',
          type: 'line' as const,
          data: closeLine.value,
          xAxisIndex: GRID_MAIN,
          yAxisIndex: 0,
          showSymbol: false,
          lineStyle: { width: 1.6, color: t.series.price },
          itemStyle: { color: t.series.price },
          areaStyle: { color: 'rgba(240,160,32,0.10)' },
        },
      ]
    : [
        {
          name: 'K线',
          type: 'candlestick' as const,
          data: candleData.value,
          xAxisIndex: GRID_MAIN,
          yAxisIndex: 0,
          itemStyle: {
            // 阳线空心：填充透明 + 红色边框（中式「空心红涨」）
            color: 'rgba(0,0,0,0)',
            color0: t.down,
            borderColor: t.up,
            borderColor0: t.down,
            borderWidth: 1,
          },
        },
        ...buildMaSeries(),
      ]

  return {
    animation: false,
    // 十字光标三图联动：专业行情图标配，也是任务书 M2 的硬性验收项
    axisPointer: {
      link: [{ xAxisIndex: 'all' }],
      label: { backgroundColor: t.crossLabel },
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      backgroundColor: t.tooltipBg,
      borderColor: t.tooltipBorder,
      textStyle: { color: t.text, fontSize: 12 },
      formatter: tooltipFormatter,
    },
    legend: {
      top: 0,
      data: intraday ? ['价格'] : ['K线', 'MA5', 'MA10', 'MA20', 'MA60'],
      textStyle: { color: t.text },
    },
    // 三个 pane 自上而下：主图 / 成交量 / 指标
    grid: [
      { left: 58, right: 62, top: 30, height: '50%' },
      { left: 58, right: 62, top: '64%', height: '12%' },
      { left: 58, right: 62, top: '79%', height: '12%' },
    ],
    xAxis: [GRID_MAIN, GRID_VOLUME, GRID_INDICATOR].map((gridIndex) => ({
      type: 'category' as const,
      data: cats,
      // 分时是连续曲线，不留间隙；K 线是离散柱，需要留
      boundaryGap: !intraday,
      gridIndex,
      axisLine: { lineStyle: { color: t.axis } },
      // 只有最下面那个 pane 显示时间标签——三个 pane 各显示一遍会挤成一团
      axisLabel: { show: gridIndex === GRID_INDICATOR, color: t.text, fontSize: 11 },
    })),
    yAxis: [
      {
        scale: true,
        gridIndex: GRID_MAIN,
        position: 'left' as const,
        ...(intraday && bounds ? { min: bounds.min, max: bounds.max } : {}),
        axisLabel: { color: t.text, fontSize: 11 },
        splitLine: { lineStyle: { color: t.split } },
      },
      {
        scale: true,
        gridIndex: GRID_MAIN,
        position: 'right' as const,
        ...(intraday && bounds
          ? { min: toPct(bounds.min), max: toPct(bounds.max) }
          : { show: false }),
        axisLabel: {
          color: t.text,
          fontSize: 11,
          formatter: (v: number) => v.toFixed(2) + '%',
        },
        splitLine: { show: false },
      },
      {
        gridIndex: GRID_VOLUME,
        splitNumber: 2,
        axisLabel: { color: t.text, fontSize: 11 },
        splitLine: { show: false },
      },
      {
        scale: true,
        gridIndex: GRID_INDICATOR,
        splitNumber: 2,
        axisLabel: { color: t.text, fontSize: 11 },
        splitLine: { show: false },
      },
    ],
    dataZoom: [
      {
        type: 'inside' as const,
        xAxisIndex: [GRID_MAIN, GRID_VOLUME, GRID_INDICATOR],
        start: zoomStart.value,
        end: 100,
        minValueSpan: 30,
        zoomLock: true,
      },
      {
        type: 'slider' as const,
        xAxisIndex: [GRID_MAIN, GRID_VOLUME, GRID_INDICATOR],
        height: 14,
        bottom: 2,
        start: zoomStart.value,
        end: 100,
        minValueSpan: 30,
        zoomLock: true,
      },
    ],
    // 草案统一在这里断言成真正的 option——内部用宽松类型是为了绕开
    // ECharts 联合类型的迭代器限制，见 SeriesDraft 的说明
    series: [
      ...mainSeries,
      ...buildVolumeSeries(),
      ...buildIndicatorSeries(),
    ] as unknown as EChartsOption['series'],
  }
})

// ---------------------------------------------------------------------------
// 生命周期：init / render / dispose
// ---------------------------------------------------------------------------

function render(): void {
  if (!chart.value) return
  // notMerge：切换指标时系列数量会变（MACD 三条 / RSI 一条），
  // 合并模式会残留上一次的系列，表现为图例里多出不该有的项
  chart.value.setOption(option.value)
}

function init(): void {
  if (!container.value || chart.value) return
  chart.value = echarts.init(container.value)
  // 用 ResizeObserver 而不是 window.resize：容器可能因为布局变化
  // （抽屉展开、侧栏收起）而改变宽度，却不触发 window 的 resize
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(container.value)
  render()
}

onMounted(() => {
  init()
  void load()
})

// 标的或周期变化：重新取数。abort 由 load() 内部处理
watch(() => [props.code, props.period], () => void load())

watch(option, render)

onBeforeUnmount(() => {
  // 先取消在途请求：组件都卸载了，回调里再写 ref 没有意义
  abortInFlight()
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})

// ---------------------------------------------------------------------------
// 交互
// ---------------------------------------------------------------------------

function selectPeriod(next: string): void {
  if (next === props.period) return
  track('quote.period.change', { period: next, code: props.code })
  emit('update:period', next)
}

function selectIndicator(next: IndicatorKey): void {
  if (next === activeIndicator.value) return
  activeIndicator.value = next
  track('quote.indicator.change', { indicator: next, code: props.code })
}

function retry(): void {
  void load()
}

defineExpose({ retry })
</script>

<template>
  <div class="stock-chart">
    <!-- 周期切换 -->
    <div class="chart-bar">
      <div class="chart-bar__group">
        <button
          v-for="p in PERIODS"
          :key="p.value"
          type="button"
          class="chip"
          :class="{ 'chip--on': p.value === period }"
          @click="selectPeriod(p.value)"
        >
          {{ p.label }}
        </button>
      </div>

      <div class="chart-bar__group">
        <button
          v-for="i in INDICATORS"
          :key="i"
          type="button"
          class="chip chip--sm"
          :class="{ 'chip--on': i === activeIndicator }"
          @click="selectIndicator(i)"
        >
          {{ i }}
        </button>
      </div>
    </div>

    <!--
      单一 ECharts 实例承载三个 pane。
      不用两个 <BaseChart> 的原因见文件头：跨实例无法联动 axisPointer。
    -->
    <div class="chart-box" :style="{ height: height + 'px' }">
      <div v-show="!isEmpty" ref="container" class="chart-canvas" />

      <div v-if="isEmpty" class="chart-mask">
        <el-empty description="该周期暂无 K 线数据" :image-size="60" />
      </div>

      <div v-if="error" class="chart-mask chart-mask--error">
        <el-alert type="error" :title="error" show-icon :closable="false" />
        <el-button :icon="Refresh" size="small" @click="retry">重试</el-button>
      </div>
    </div>

    <p class="chart-hint muted">{{ spec.hint }}</p>
  </div>
</template>

<style scoped>
.chart-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.chart-bar__group {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.chip {
  padding: 3px 10px;
  font-size: 12px;
  line-height: 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-regular);
  cursor: pointer;
}

.chip--sm {
  padding: 1px 8px;
  font-size: 11px;
}

.chip:hover {
  border-color: var(--color-up);
}

.chip--on {
  background: var(--color-up);
  border-color: var(--color-up);
  color: #fff;
  font-weight: 600;
}

.chart-box {
  position: relative;
  width: 100%;
}

.chart-canvas {
  width: 100%;
  height: 100%;
}

.chart-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--color-surface);
}

.chart-mask--error {
  align-items: stretch;
  justify-content: center;
  background: color-mix(in srgb, var(--color-surface) 92%, transparent);
  padding: 0 12px;
}

.chart-hint {
  margin: 6px 0 0;
  font-size: 12px;
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Star, MagicStick, Wallet } from '@element-plus/icons-vue'
import type { EChartsOption } from 'echarts'
import BaseChart from '@/components/BaseChart.vue'
import ChangeText from '@/components/ChangeText.vue'
import TradeDialog from '@/components/TradeDialog.vue'
import { fundamentalApi, indicatorApi, quoteApi, watchlistApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import type {
  Bar,
  DupontAnalysis,
  FundamentalOverview,
  IndicatorResponse,
  Quote,
} from '@/api/types'
import { formatEpochSeconds } from '@/api/types'
import { formatMoney, formatNumber, formatVolume } from '@/utils/format'

/**
 * 个股详情。
 *
 * 数据分四块，各自独立加载：报价与 K 线（核心，失败要显式报错）、
 * 技术指标、基本面、以及 WebSocket 实时推送。
 * 拆开加载的原因：基本面依赖的财务接口比行情接口更容易不可用，
 * 不能因为财务取不到就让整个页面白屏。
 */
const props = defineProps<{ code: string }>()

const router = useRouter()
const auth = useAuthStore()
const socket = useQuoteSocketStore()

const quote = ref<Quote | null>(null)
const bars = ref<Bar[]>([])
const indicators = ref<IndicatorResponse | null>(null)
const overview = ref<FundamentalOverview | null>(null)
const dupont = ref<DupontAnalysis | null>(null)

const loadingQuote = ref(false)
const loadingBars = ref(false)
const loadingIndicator = ref(false)
const loadingFundamental = ref(false)
const loadError = ref<string | null>(null)

const period = ref('daily')
const activeIndicator = ref<'MACD' | 'RSI' | 'BOLL' | 'KDJ'>('MACD')

const tradeVisible = ref(false)
const tradeMode = ref<'buy' | 'sell'>('buy')

const PERIODS = [
  { label: '日K', value: 'daily' },
  { label: '周K', value: 'weekly' },
  { label: '月K', value: 'monthly' },
  { label: '60分', value: '60min' },
  { label: '30分', value: '30min' },
  { label: '15分', value: '15min' },
  { label: '5分', value: '5min' },
  { label: '分时', value: 'trend' },
]

/**
 * 优先用 WebSocket 推送的最新价，推送还没到时回退到 REST 报价。
 * 两个数据源的字段结构一致，可以无缝替换。
 */
const live = computed(() => socket.quotes[props.code] ?? null)

const displayName = computed(() => live.value?.name ?? quote.value?.name ?? '--')
const displayPrice = computed(() => live.value?.price ?? quote.value?.price ?? null)
const displayChange = computed(() => live.value?.change ?? quote.value?.change ?? null)
const displayChangePct = computed(() => live.value?.changePct ?? quote.value?.changePct ?? null)
const displayTs = computed(() => live.value?.ts ?? quote.value?.ts ?? null)

const hasIndicator = computed(() => indicators.value !== null)

onMounted(() => {
  void loadAll()
})

// 切换标的时重新拉全部数据，并切换实时订阅
watch(
  () => props.code,
  () => void loadAll(),
)

async function loadAll(): Promise<void> {
  loadError.value = null
  await Promise.allSettled([
    loadQuote(),
    loadBars(),
    loadIndicators(),
    loadFundamental(),
  ])
  subscribeLive()
}

function subscribeLive(): void {
  // acquire 返回释放函数；标的切换时先释放旧的
  releaseLive?.()
  releaseLive = socket.acquire([props.code])
}

let releaseLive: (() => void) | null = null

async function loadQuote(): Promise<void> {
  loadingQuote.value = true
  try {
    quote.value = await quoteApi.quote(props.code)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '报价加载失败'
  } finally {
    loadingQuote.value = false
  }
}

async function loadBars(): Promise<void> {
  loadingBars.value = true
  try {
    bars.value = await quoteApi.bars(props.code, { period: period.value, limit: 180 })
  } catch (e) {
    bars.value = []
    loadError.value = e instanceof Error ? e.message : 'K 线加载失败'
  } finally {
    loadingBars.value = false
  }
}

async function loadIndicators(): Promise<void> {
  loadingIndicator.value = true
  try {
    indicators.value = await indicatorApi.get(props.code, ['MACD', 'RSI', 'BOLL', 'KDJ', 'MA'], 180)
  } catch {
    indicators.value = null
  } finally {
    loadingIndicator.value = false
  }
}

async function loadFundamental(): Promise<void> {
  loadingFundamental.value = true
  try {
    const [ov, dp] = await Promise.allSettled([
      fundamentalApi.overview(props.code),
      fundamentalApi.dupont(props.code),
    ])
    overview.value = ov.status === 'fulfilled' ? ov.value : null
    dupont.value = dp.status === 'fulfilled' ? dp.value : null
  } finally {
    loadingFundamental.value = false
  }
}

async function onPeriodChange(): Promise<void> {
  await loadBars()
}

async function addToWatchlist(): Promise<void> {
  if (!auth.isLoggedIn) {
    ElMessage.info('请先登录后再添加自选')
    router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  try {
    await watchlistApi.addItem(props.code)
    ElMessage.success('已加入自选')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  }
}

function openTrade(mode: 'buy' | 'sell'): void {
  if (!auth.isLoggedIn) {
    ElMessage.info('请先登录后再记账')
    router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  tradeMode.value = mode
  tradeVisible.value = true
}

// ---------------------------------------------------------------------------
// 图表配置
// ---------------------------------------------------------------------------

const UP_COLOR = '#d92b2b'
const DOWN_COLOR = '#0a9e5c'

const dates = computed(() => bars.value.map((b) => b.date))

/**
 * K 线图。
 *
 * <b>颜色约定</b>：A 股是「阳线红、阴线绿」，与欧美相反。
 * ECharts 的 candlestick 默认恰好是红涨，但仍显式写死颜色：
 * 依赖默认值的话，将来换主题或升级版本时可能悄悄反转，
 * 而这类错误在图上「看起来完全正常」。
 *
 * 数据顺序是 [open, close, low, high] —— 不是 OHLC，是 OCLH。
 * 写反了图形会整体失真，且不一定报错。
 */
const klineOption = computed<EChartsOption>(() => {
  const ma = indicators.value?.ma
  const series: EChartsOption['series'] = [
    {
      name: 'K线',
      type: 'candlestick',
      data: bars.value.map((b) => [b.open, b.close, b.low, b.high]),
      itemStyle: {
        color: UP_COLOR,
        color0: DOWN_COLOR,
        borderColor: UP_COLOR,
        borderColor0: DOWN_COLOR,
      },
    },
  ]

  if (ma) {
    const maDefs: Array<[string, number[] | undefined, string]> = [
      ['MA5', ma.ma5, '#e6a23c'],
      ['MA10', ma.ma10, '#409eff'],
      ['MA20', ma.ma20, '#9b59b6'],
      ['MA60', ma.ma60, '#606266'],
    ]
    for (const [name, data, color] of maDefs) {
      if (!data?.length) continue
      series.push({
        name,
        type: 'line',
        data,
        smooth: true,
        showSymbol: false,
        lineStyle: { width: 1, color },
        itemStyle: { color },
      })
    }
  }

  return {
    animation: false,
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { top: 0, data: ['K线', 'MA5', 'MA10', 'MA20', 'MA60'] },
    grid: { left: 56, right: 20, top: 36, bottom: 48 },
    xAxis: {
      type: 'category',
      data: dates.value,
      boundaryGap: true,
      axisLine: { lineStyle: { color: '#dcdfe6' } },
    },
    yAxis: {
      type: 'value',
      // scale: true —— 股价波动区间往往远小于 0 到最高价，
      // 不从 0 起算才能看出真实形态
      scale: true,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
    },
    dataZoom: [
      { type: 'inside', start: 60, end: 100 },
      { type: 'slider', height: 18, bottom: 8, start: 60, end: 100 },
    ],
    series,
  }
})

/** 副图指标。三个分支的 series 数量不同，所以每次都是全量替换（notMerge）。 */
const indicatorOption = computed<EChartsOption>(() => {
  const ind = indicators.value
  const base: EChartsOption = {
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: 56, right: 20, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates.value,
      axisLine: { lineStyle: { color: '#dcdfe6' } },
    },
    yAxis: { type: 'value', scale: true, splitLine: { lineStyle: { color: '#f0f2f5' } } },
  }

  if (!ind) return base

  if (activeIndicator.value === 'MACD' && ind.macd) {
    return {
      ...base,
      legend: { top: 0, data: ['DIF', 'DEA', 'MACD'] },
      series: [
        { name: 'DIF', type: 'line', data: ind.macd.dif, showSymbol: false, lineStyle: { width: 1 } },
        { name: 'DEA', type: 'line', data: ind.macd.dea, showSymbol: false, lineStyle: { width: 1 } },
        {
          name: 'MACD',
          type: 'bar',
          // 柱状图也遵循涨跌色：正值红、负值绿。
          // 这里用「逐项 itemStyle」而不是 series.itemStyle.color 回调：
          // ECharts 的 CallbackDataParams.value 是联合类型
          // （string | number | Date | OptionDataValue[] | ... | null | undefined），
          // 写成 (params: { value: number }) => ... 会因参数逆变检查而报 TS2769。
          // 逐项指定既规避了这个类型坑，意图也更直白。
          data: ind.macd.macd.map((v) => ({
            value: v,
            itemStyle: { color: v >= 0 ? UP_COLOR : DOWN_COLOR },
          })),
        },
      ],
    }
  }

  if (activeIndicator.value === 'RSI' && ind.rsi) {
    return {
      ...base,
      legend: { top: 0, data: ['RSI'] },
      series: [{ name: 'RSI', type: 'line', data: ind.rsi, showSymbol: false, lineStyle: { width: 1.5 } }],
    }
  }

  if (activeIndicator.value === 'BOLL' && ind.boll) {
    return {
      ...base,
      legend: { top: 0, data: ['上轨', '中轨', '下轨'] },
      series: [
        { name: '上轨', type: 'line', data: ind.boll.upper, showSymbol: false, lineStyle: { width: 1 } },
        { name: '中轨', type: 'line', data: ind.boll.mid, showSymbol: false, lineStyle: { width: 1.5 } },
        { name: '下轨', type: 'line', data: ind.boll.lower, showSymbol: false, lineStyle: { width: 1 } },
      ],
    }
  }

  if (activeIndicator.value === 'KDJ' && ind.kdj) {
    return {
      ...base,
      legend: { top: 0, data: ['K', 'D', 'J'] },
      series: [
        { name: 'K', type: 'line', data: ind.kdj.k, showSymbol: false, lineStyle: { width: 1 } },
        { name: 'D', type: 'line', data: ind.kdj.d, showSymbol: false, lineStyle: { width: 1 } },
        { name: 'J', type: 'line', data: ind.kdj.j, showSymbol: false, lineStyle: { width: 1 } },
      ],
    }
  }

  return base
})

/** MACD 柱状的 color 回调参数类型在 ECharts 里是联合类型，这里放宽以便使用。 */
const signalEntries = computed(() => {
  const s = indicators.value?.signals
  if (!s) return []
  const label: Record<string, string> = {
    macd: 'MACD',
    rsi: 'RSI',
    boll: 'BOLL',
    kdj: 'KDJ',
    ma: '均线',
  }
  return Object.entries(s)
    .filter(([, v]) => Boolean(v))
    .map(([k, v]) => ({ key: k, label: label[k] ?? k.toUpperCase(), text: v as string }))
})
</script>

<template>
  <div class="page">
    <!-- 报价头部 -->
    <section class="card quote-head">
      <div class="quote-head__left">
        <div class="quote-head__title">
          <h2>{{ displayName }}</h2>
          <span class="mono muted">{{ props.code }}</span>
          <el-tag v-if="live" type="success" size="small" effect="plain">实时</el-tag>
        </div>
        <div class="quote-head__price">
          <ChangeText :value="displayPrice" :show-plus="false" :digits="2" class="big" />
          <ChangeText :value="displayChange" />
          <ChangeText :value="displayChangePct" suffix="%" />
          <span class="muted" style="font-size: 12px">
            更新于 {{ formatEpochSeconds(displayTs) }}
          </span>
        </div>
      </div>

      <div class="quote-head__actions">
        <el-button :icon="Star" @click="addToWatchlist">加自选</el-button>
        <el-button :icon="Wallet" @click="openTrade('buy')">记买入</el-button>
        <el-button :icon="Wallet" @click="openTrade('sell')">记卖出</el-button>
        <el-button
          type="primary"
          :icon="MagicStick"
          @click="router.push({ name: 'analysis-detail', params: { code: props.code } })"
        >
          AI 研判
        </el-button>
      </div>

      <div class="quote-head__stats">
        <div><span class="muted">今开</span> <span class="num">{{ formatNumber(quote?.open) }}</span></div>
        <div><span class="muted">最高</span> <span class="num">{{ formatNumber(quote?.high) }}</span></div>
        <div><span class="muted">最低</span> <span class="num">{{ formatNumber(quote?.low) }}</span></div>
        <div><span class="muted">昨收</span> <span class="num">{{ formatNumber(quote?.prevClose) }}</span></div>
        <div><span class="muted">换手率</span> <span class="num">{{ formatNumber(quote?.turnover) }}%</span></div>
        <div><span class="muted">成交量</span> <span class="num">{{ formatVolume(quote?.volume) }}</span></div>
        <div><span class="muted">成交额</span> <span class="num">{{ formatMoney(quote?.amount) }}</span></div>
      </div>

      <el-alert
        v-if="loadError"
        type="error"
        :title="loadError"
        show-icon
        :closable="false"
        style="margin-top: 12px"
      />
    </section>

    <!-- K 线 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">K 线（含均线）</span>
        <el-radio-group v-model="period" size="small" @change="onPeriodChange">
          <el-radio-button v-for="p in PERIODS" :key="p.value" :value="p.value">
            {{ p.label }}
          </el-radio-button>
        </el-radio-group>
      </div>
      <div class="card__body">
        <BaseChart
          :option="klineOption"
          :height="380"
          :loading="loadingBars"
          :is-empty="!bars.length && !loadingBars"
          empty-text="该周期暂无 K 线数据"
        />
      </div>
    </section>

    <!-- 技术指标 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">技术指标</span>
        <el-radio-group v-model="activeIndicator" size="small">
          <el-radio-button value="MACD">MACD</el-radio-button>
          <el-radio-button value="RSI">RSI</el-radio-button>
          <el-radio-button value="BOLL">BOLL</el-radio-button>
          <el-radio-button value="KDJ">KDJ</el-radio-button>
        </el-radio-group>
      </div>
      <div class="card__body">
        <BaseChart
          :option="indicatorOption"
          :height="260"
          :loading="loadingIndicator"
          :is-empty="!hasIndicator && !loadingIndicator"
          empty-text="指标数据不可用"
        />

        <div v-if="signalEntries.length" class="signals">
          <el-tag
            v-for="s in signalEntries"
            :key="s.key"
            size="small"
            type="info"
            effect="plain"
            class="signal"
          >
            <strong>{{ s.label }}</strong>
            {{ s.text }}
          </el-tag>
        </div>
      </div>
    </section>

    <!-- 基本面 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">财务基本面</span>
        <!-- dataSource 必须展示：真实财报与估算值混在同一组字段里 -->
        <el-tag
          v-if="overview?.dataSource"
          :type="overview.dataSource === 'estimated' ? 'warning' : 'success'"
          size="small"
        >
          {{ overview.dataSource === 'estimated' ? '估算值（非财报原始数据）' : overview.dataSource }}
        </el-tag>
      </div>
      <div class="card__body">
        <el-skeleton v-if="loadingFundamental" :rows="4" animated />
        <template v-else>
          <el-alert
            v-if="overview?.notice"
            type="warning"
            :title="overview.notice"
            show-icon
            :closable="false"
            style="margin-bottom: 12px"
          />

          <div class="grid grid--4">
            <div class="metric">
              <div class="metric__label">市盈率 PE</div>
              <div class="metric__value num">{{ formatNumber(overview?.pe) }}</div>
            </div>
            <div class="metric">
              <div class="metric__label">市净率 PB</div>
              <div class="metric__value num">{{ formatNumber(overview?.pb) }}</div>
            </div>
            <div class="metric">
              <div class="metric__label">ROE</div>
              <div class="metric__value num">{{ formatNumber(overview?.roe) }}%</div>
            </div>
            <div class="metric">
              <div class="metric__label">
                毛利率
                <!-- 银行、券商没有「毛利率」，此时后端标记不适用，
                     而不是返回一个看起来正常其实无意义的数字 -->
                <el-tooltip
                  v-if="overview && !overview.grossMarginApplicable"
                  content="该行业无毛利率概念，数值不适用"
                >
                  <span class="muted">(不适用)</span>
                </el-tooltip>
              </div>
              <div class="metric__value num">
                {{
                  overview?.grossMarginApplicable === false
                    ? '--'
                    : `${formatNumber(overview?.grossMargin)}%`
                }}
              </div>
            </div>
            <div class="metric">
              <div class="metric__label">净利率</div>
              <div class="metric__value num">{{ formatNumber(overview?.netMargin) }}%</div>
            </div>
            <div class="metric">
              <div class="metric__label">资产负债率</div>
              <div class="metric__value num">{{ formatNumber(overview?.debtRatio) }}%</div>
            </div>
          </div>

          <el-divider content-position="left">杜邦分解</el-divider>

          <div v-if="dupont" class="dupont">
            <div class="grid grid--4">
              <div class="metric">
                <div class="metric__label">ROE</div>
                <div class="metric__value num">{{ formatNumber(dupont.roe) }}%</div>
              </div>
              <div class="metric">
                <div class="metric__label">销售净利率</div>
                <div class="metric__value num">{{ formatNumber(dupont.netMargin) }}%</div>
              </div>
              <div class="metric">
                <div class="metric__label">总资产周转率</div>
                <div class="metric__value num">{{ formatNumber(dupont.assetTurnover, 4) }}</div>
              </div>
              <div class="metric">
                <div class="metric__label">权益乘数</div>
                <div class="metric__value num">{{ formatNumber(dupont.equityMultiplier, 4) }}</div>
              </div>
            </div>
            <p class="dupont__note muted">
              恒等式：ROE = 销售净利率 × 总资产周转率 × 权益乘数，
              三项相乘应与上方 ROE 一致（后端已做闭合处理）。
            </p>
            <p v-if="dupont.interpretation" class="dupont__interpretation">
              {{ dupont.interpretation }}
            </p>
          </div>
          <el-empty v-else description="暂无杜邦分析数据" :image-size="64" />
        </template>
      </div>
    </section>

    <TradeDialog v-model="tradeVisible" :mode="tradeMode" :code="props.code" :name="displayName" @done="loadAll" />
  </div>
</template>

<style scoped>
.quote-head__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quote-head__title h2 {
  margin: 0;
  font-size: 22px;
}

.quote-head__price {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-top: 6px;
}

.quote-head__price :deep(.big) {
  font-size: 30px;
  font-weight: 700;
}

.quote-head__actions {
  display: flex;
  gap: 8px;
  margin: 14px 0;
}

.quote-head__stats {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 8px 16px;
  font-size: 13px;
  border-top: 1px solid var(--color-border);
  padding-top: 12px;
}

.signals {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.signal {
  white-space: normal;
  height: auto;
  line-height: 1.5;
  padding: 4px 8px;
}

.metric {
  padding: 10px 12px;
  background: #fafbfc;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.metric__label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.metric__value {
  font-size: 17px;
  font-weight: 600;
  margin-top: 2px;
}

.dupont__note,
.dupont__interpretation {
  margin: 10px 0 0;
  font-size: 12px;
}

.dupont__interpretation {
  color: var(--color-text-regular);
}
</style>

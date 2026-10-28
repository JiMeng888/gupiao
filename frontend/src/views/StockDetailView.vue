<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AiInsightCard from '@/components/AiInsightCard.vue'
import QuoteHeader from '@/components/QuoteHeader.vue'
import StockChart from '@/components/StockChart.vue'
import TradeDialog from '@/components/TradeDialog.vue'
import { fundamentalApi, indicatorApi, quoteApi, watchlistApi } from '@/api/endpoints'
import { useAuthStore } from '@/stores/auth'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import type {
  DupontAnalysis,
  FundamentalOverview,
  IndicatorResponse,
  Quote,
  QuoteSnapshot,
} from '@/api/types'
import { formatNumber } from '@/utils/format'

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
const indicators = ref<IndicatorResponse | null>(null)
const overview = ref<FundamentalOverview | null>(null)
const dupont = ref<DupontAnalysis | null>(null)

const loadingQuote = ref(false)
const loadingIndicator = ref(false)
const loadingFundamental = ref(false)
const loadError = ref<string | null>(null)

/**
 * K 线周期。只在这里持有「当前值」，取数由 {@code StockChart} 自己完成——
 * 图表组件内部才知道每个周期要多少根、走不走分时形态，
 * 让页面代管这些参数会让两边各维护一份口径。
 */
const period = ref('daily')

const tradeVisible = ref(false)
const tradeMode = ref<'buy' | 'sell'>('buy')

/** WebSocket 推送的最新快照；没推送时为 null。 */
const live = computed(() => socket.quotes[props.code] ?? null)

/**
 * 展示用报价：有实时推送时优先用推送值。
 *
 * <p>这里做<b>字段级回退</b>而不是整体替换：推送包里只带价格相关的几个字段，
 * 像「今开 / 最高 / 最低 / 总市值」这些它不给，
 * 整体替换会让这些格子变成 --；逐字段取才能保证既有实时性又不丢静态字段。
 */
const mergedQuote = computed<QuoteSnapshot | null>(() => {
  const rest = quote.value
  const push = live.value
  if (!rest) return push
  if (!push) return rest
  return {
    ...rest,
    name: push.name || rest.name,
    price: push.price,
    change: push.change,
    changePct: push.changePct,
    volume: push.volume,
    // 推送帧的 amount 可为 null（上游未给成交额），此时保留 REST 侧的值，
    // 而不是填 0——「成交额 0」是错误信息，会让人以为当天没有成交
    amount: push.amount ?? rest.amount,
    ts: push.ts,
  }
})

function goAnalysis(): void {
  router.push({ name: 'analysis-detail', params: { code: props.code } })
}

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
  await Promise.allSettled([loadQuote(), loadIndicators(), loadFundamental()])
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

/** 把指标信号对象摊平成可遍历的标签列表，空信号过滤掉。 */
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
    <!--
      报价头部（M1）。延迟标注、总市值、市盈率 TTM 都收在这个组件里：
      合规要求「延迟时长必须显示在价格附近」，所以这些标签不能挪到别处。
      quote 传入的是合并了 WebSocket 推送后的展示值，见 mergedQuote。
    -->
    <QuoteHeader
      :code="props.code"
      :quote="mergedQuote"
      :overview="overview"
      :live="live !== null"
      @add-watchlist="addToWatchlist"
      @trade="openTrade"
      @analysis="goAnalysis"
    />

    <el-alert
      v-if="loadError"
      type="error"
      :title="loadError"
      show-icon
      :closable="false"
    />

    <!--
      K 线 + 成交量 + 指标：三 pane 联动（M2）。
      周期与指标状态都由图表组件自己持有，页面不再代管。
    -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">行情走势</span>
      </div>
      <div class="card__body">
        <StockChart
          :code="props.code"
          :period="period"
          :indicators="indicators"
          :height="440"
          @update:period="period = $event"
        />
      </div>
    </section>

    <!-- 技术指标信号 -->
    <section class="card">
      <div class="card__header">
        <span class="card__title">技术信号</span>
      </div>
      <div class="card__body">
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
        <p v-else class="muted" style="margin: 0">当前无可用技术信号</p>
      </div>
    </section>

    <!--
      AI 解读卡（M3）。
      放在技术信号之后：用户先看到客观的量价与指标，再看 AI 的主观解读，
      顺序反过来会让 AI 的结论先入为主地影响对图形的判读。
    -->
    <AiInsightCard :code="props.code" :name="mergedQuote?.name ?? ''" />

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

    <TradeDialog
      v-model="tradeVisible"
      :mode="tradeMode"
      :code="props.code"
      :name="mergedQuote?.name ?? ''"
      @done="loadAll"
    />
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
  background: var(--color-surface-muted);
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

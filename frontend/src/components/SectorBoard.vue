<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { quoteApi } from '@/api/endpoints'
import type { Sector } from '@/api/types'
import { useMarketSourceStore } from '@/stores/marketSource'
import { providerLabel, servingProvider } from '@/utils/marketSourceLabels'
import { track } from '@/utils/track'
import { storageKey } from '@/utils/storage'

/**
 * 板块榜单（任务书 M4，对应走查 P1 的行情覆盖度缺口）。
 *
 * <h2>为什么必须带上涨/下跌家数</h2>
 * 板块涨跌幅是个<b>加权平均值</b>，它天然掩盖内部分化：
 * 一个板块 +2%，可能是 80 只票普涨，也可能是一只权重股涨停、
 * 其余 79 只在跌。这两种情形对决策的含义完全相反，
 * 而只看涨跌幅区分不出来。因此这里把 upCount / downCount 与涨跌幅
 * 并排展示，让用户自己能校验。
 *
 * <h2>为什么「上游不可用时显示原因」而不是显示空表</h2>
 * 后端在降级时<b>刻意</b>拒绝返回板块榜单（演示数据不能用于排序，
 * 见后端的 `MarketDataProvider#acceptableAsFallback`），此时返回 503。
 * 如果前端拿到 503 只渲染一个空表格，用户会理解成
 * 「今天这个板块没涨」——这是把一个故障读成了市场信息。
 * 所以必须把 503 的原因原样展示。
 *
 * <h2>为什么标注数据来源</h2>
 * 板块榜有<b>自己的</b>优先链（腾讯一级行业优先，东财细分行业兜底），
 * 与报价/K 线的数据源不是同一件事。同一个面板在不同时间可能由不同源服务，
 * 板块名的粒度也随之变化（申万一级 vs 东财三级行业）。
 * 不标出来，用户只会觉得「板块名怎么变了」，而这是个无法自证的问题。
 *
 * <h2>为什么排序/涨跌条在前端重算</h2>
 * 后端按涨跌幅降序返回，前端只做「相对最大值的条形长度」换算，
 * 不重新排序——避免前端与后端对「前 N 名」给出不同结果。
 */
const props = withDefaults(
  defineProps<{
    /** 展示条数，1~20；后端同样有校验，这里再兜一次 */
    limit?: number
    /** 紧凑模式：去掉涨跌家数列，用于窄栏 */
    compact?: boolean
  }>(),
  {
    limit: 10,
    compact: false,
  },
)

const sectors = ref<Sector[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const router = useRouter()
const source = useMarketSourceStore()

/** 上次成功刷新的时刻；刷新失败时不覆盖，避免显示成「刚刚更新」。 */
const updatedAt = ref<Date | null>(null)

/**
 * 记住用户上次选择的展示条数。
 *
 * <p>这是纯偏好，丢了也不影响功能，但每次都退回 10 条很烦。
 * 写入前 try/catch：隐私模式下 localStorage 可能抛异常，
 * 一个偏好设置不该让整个榜单渲染失败。
 */
function saveLimit(next: number): void {
  try {
    localStorage.setItem(storageKey('market', 'sectorLimit'), String(next))
  } catch {
    /* 忽略 */
  }
}

function loadLimit(): number | null {
  try {
    const raw = localStorage.getItem(storageKey('market', 'sectorLimit'))
    const n = raw ? Number(raw) : NaN
    return Number.isFinite(n) ? n : null
  } catch {
    return null
  }
}

const LIMIT_OPTIONS = [5, 10, 15, 20]

const effectiveLimit = ref(loadLimit() ?? props.limit)

async function load(): Promise<void> {
  loading.value = true
  try {
    sectors.value = await quoteApi.sectors(effectiveLimit.value)
    error.value = null
    updatedAt.value = new Date()
  } catch (e) {
    // 拿不到就清空，绝不拿上一次的残留数据冒充当前榜单
    sectors.value = []
    error.value = e instanceof Error ? e.message : '板块数据暂不可用'
  } finally {
    loading.value = false
  }
}

function changeLimit(next: number): void {
  effectiveLimit.value = next
  saveLimit(next)
  track('market.sector.limit.change', { limit: next })
  void load()
}

function retry(): void {
  track('market.sector.retry', {})
  void load()
}

onMounted(() => void load())

/** 条形长度的换算基准：本榜内最大绝对涨跌幅，避免除零。 */
const maxAbs = computed(() => {
  const values = sectors.value.map((s) => Math.abs(s.changePct)).filter(Number.isFinite)
  return Math.max(1e-6, ...values)
})

/** 相对条宽（0~100）。用绝对值——涨跌方向由颜色表达，不由长度表达。 */
function barWidth(pct: number): number {
  return Math.min(100, (Math.abs(pct) / maxAbs.value) * 100)
}

/** 涨跌家数：上游可能不给，此时如实显示「—」而不是 0。 */
function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value)
}

/** 更新时间文案。 */
const updatedText = computed(() => {
  if (!updatedAt.value) return '尚未更新'
  const pad = (n: number) => String(n).padStart(2, '0')
  const d = updatedAt.value
  return `更新于 ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

/**
 * 本板块区实际由哪个源服务。
 *
 * <p>为什么不直接用顶栏的「主数据源」：降级是<b>按操作</b>发生的。
 * 板块榜本身就配了独立的优先链（腾讯一级行业优先），因此它和报价
 * 的来源本来就可能不同——把顶栏的 provider 当成板块来源会张冠李戴。
 * 这里读 {@code servingByAction.getSectors}，没有就是「还没拉到状态」，
 * 不猜、不显示。
 */
const servingSource = computed(() => servingProvider(source.status, 'getSectors'))

const sourceText = computed(() => {
  const label = providerLabel(servingSource.value)
  return label ? `数据源：${label}` : null
})

/** 演示数据要显式标红：板块排名是排序结果，编造的排序比乱码更危险。 */
const sourceIsDemo = computed(() => servingSource.value === 'mock')

/**
 * 上涨家数占比（0~100），用于「普涨还是分化」的一眼判断。
 *
 * <p>两边都缺失时返回 null（不画条），而不是 0——
 * 0% 会被读成「全在跌」，那是把一个缺失读成了市场结论。
 */
function upRatio(s: Sector): number | null {
  const up = s.upCount
  const down = s.downCount
  if (up === null || up === undefined || down === null || down === undefined) return null
  const total = up + down
  if (total <= 0) return null
  return (up / total) * 100
}

function breadthTitle(s: Sector): string {
  const ratio = upRatio(s)
  if (ratio === null) return '上游未提供涨跌家数'
  return `上涨 ${s.upCount} 家，下跌 ${s.downCount} 家（上涨占比 ${ratio.toFixed(0)}%）`
}

function openStock(code: string): void {
  track('market.sector.leader.open', { code })
  void router.push({ name: 'stock-detail', params: { code } })
}
</script>

<template>
  <section class="card sector">
    <div class="card__header">
      <span class="card__title">热门板块</span>

      <div class="sector__controls">
        <!--
          数据来源标注。板块榜的优先链与主链不同，且可能在运行中切换源，
          不标出来的话「为什么板块名和昨天不一样」永远解释不清。
        -->
        <el-tag
          v-if="sourceText"
          size="small"
          :type="sourceIsDemo ? 'danger' : 'info'"
          effect="plain"
          class="sector__source"
        >
          {{ sourceText }}
        </el-tag>
        <span class="muted sector__updated">{{ updatedText }}</span>
        <el-select
          :model-value="effectiveLimit"
          size="small"
          class="sector__select"
          @change="changeLimit"
        >
          <el-option v-for="n in LIMIT_OPTIONS" :key="n" :label="`前 ${n}`" :value="n" />
        </el-select>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="retry">
          刷新
        </el-button>
      </div>
    </div>

    <div class="card__body">
      <!--
        错误优先展示。
        这里的 503 不是「出了意外」，而是后端明确拒绝用演示数据排序，
        属于设计内的行为，因此文案要说清「拿不到真实排名」而不是「加载失败」。
        附一个重试入口：上游抖动是最常见的原因，而重试是用户此时唯一能做的事。
      -->
      <div v-if="error" class="sector__error">
        <el-alert
          type="warning"
          title="暂无法获取板块排名"
          :description="error"
          show-icon
          :closable="false"
        />
        <el-button size="small" :loading="loading" class="sector__error-retry" @click="retry">
          重试
        </el-button>
      </div>

      <div v-else-if="loading && !sectors.length" class="sector__loading">
        <el-skeleton :rows="4" animated />
      </div>

      <el-empty v-else-if="!sectors.length" description="暂无板块数据" />

      <ul v-else class="sector__list">
        <li v-for="(s, i) in sectors" :key="s.code" class="sector__row">
          <span class="sector__rank" :class="{ 'sector__rank--top': i < 3 }">{{ i + 1 }}</span>

          <span class="sector__name" :title="s.code">{{ s.name }}</span>

          <!--
            涨跌条：长度表示强弱，颜色表示方向。
            用绝对值算长度是刻意的——如果长度带方向（负值向左），
            在只有正收益的榜单里几乎看不出差异。
          -->
          <span class="sector__bar" aria-hidden="true">
            <span
              class="sector__bar-fill"
              :class="s.changePct >= 0 ? 'sector__bar-fill--up' : 'sector__bar-fill--down'"
              :style="{ width: barWidth(s.changePct) + '%' }"
            />
          </span>

          <span class="sector__pct num" :class="s.changePct >= 0 ? 'text-up' : 'text-down'">
            {{ s.changePct >= 0 ? '+' : '' }}{{ s.changePct.toFixed(2) }}%
          </span>

          <!--
            涨跌家数：数字 + 一根占比条。
            只看数字需要心算「37/122 算多还是少」，加一根条之后
            「普涨 / 分化 / 普跌」是一眼可读的。缺失时只显示「—」并不画条。
          -->
          <span v-if="!compact" class="sector__breadth" :title="breadthTitle(s)">
            <span class="sector__breadth-text muted num">
              {{ formatCount(s.upCount) }} / {{ formatCount(s.downCount) }}
            </span>
            <span v-if="upRatio(s) !== null" class="sector__breadth-bar" aria-hidden="true">
              <span class="sector__breadth-up" :style="{ width: upRatio(s) + '%' }" />
            </span>
          </span>

          <!--
            领涨股：有代码时可直接跳到个股页。
            板块榜本身不构成操作依据，「谁在带」才是用户下一步要看的东西。
          -->
          <span v-if="!compact" class="sector__leader muted">
            <template v-if="s.leaderName">
              <button
                v-if="s.leaderCode"
                type="button"
                class="sector__leader-link"
                :title="`查看 ${s.leaderName} 详情`"
                @click="openStock(s.leaderCode)"
              >
                {{ s.leaderName }}
              </button>
              <span v-else>{{ s.leaderName }}</span>
              <span v-if="s.leaderPct !== null && s.leaderPct !== undefined" class="num">
                {{ s.leaderPct >= 0 ? '+' : '' }}{{ s.leaderPct.toFixed(2) }}%
              </span>
            </template>
            <template v-else>—</template>
          </span>
        </li>
      </ul>

      <p class="muted sector__foot">
        涨跌幅为板块内成分股加权结果；右侧「涨 / 跌」为成分股上涨与下跌家数，
        用于判断板块是普涨还是被个别权重股拉动。上游缺失时显示「—」，不做推断。
      </p>
    </div>
  </section>
</template>

<style scoped>
.sector__controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sector__updated {
  font-size: 12px;
}

.sector__source {
  font-size: 12px;
}

.sector__error {
  display: flex;
  align-items: center;
  gap: 12px;
}

.sector__error :deep(.el-alert) {
  flex: 1;
}

.sector__error-retry {
  flex: none;
}

.sector__select {
  width: 84px;
}

.sector__loading {
  padding: 4px 0;
}

.sector__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.sector__row {
  display: grid;
  grid-template-columns: 24px minmax(72px, 1.2fr) minmax(60px, 1.8fr) 74px 72px minmax(96px, 1.4fr);
  align-items: center;
  gap: 10px;
  padding: 7px 2px;
  border-bottom: 1px solid var(--color-border);
  font-size: 13px;
}

.sector__row:last-child {
  border-bottom: none;
}

.sector__rank {
  text-align: center;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.sector__rank--top {
  color: var(--color-up);
  font-weight: 700;
}

.sector__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sector__bar {
  height: 8px;
  border-radius: 4px;
  background: var(--color-border);
  overflow: hidden;
}

.sector__bar-fill {
  display: block;
  height: 100%;
  border-radius: 4px;
}

.sector__bar-fill--up {
  background: var(--color-up);
}

.sector__bar-fill--down {
  background: var(--color-down);
}

.sector__pct {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.sector__breadth {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  font-size: 12px;
}

.sector__breadth-text {
  font-variant-numeric: tabular-nums;
}

/* 占比条：宽度上限 100%，高度只有 3px——它是辅助读数，不该抢涨跌幅的视觉权重 */
.sector__breadth-bar {
  display: block;
  width: 100%;
  height: 3px;
  border-radius: 2px;
  background: var(--color-down);
  overflow: hidden;
}

.sector__breadth-up {
  display: block;
  height: 100%;
  background: var(--color-up);
}

.sector__leader {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

/* 用 button 而不是 a：没有 href 却长得像链接的 <a> 会被键盘用户当成可跳转锚点 */
.sector__leader-link {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  color: inherit;
  cursor: pointer;
}

/* 原来引的是 --color-primary，这个变量全项目并不存在，
   于是一直吃 #409eff 兜底值——深色主题改了主色它也跟不上。
   项目里实际定义主色的是 Element Plus 的 --el-color-primary。 */
.sector__leader-link:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}

.sector__leader-link:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
  border-radius: 2px;
}

.sector__foot {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
</style>

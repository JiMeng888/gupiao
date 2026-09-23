<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Refresh } from '@element-plus/icons-vue'
import ChangeText from '@/components/ChangeText.vue'
import TradeDialog from '@/components/TradeDialog.vue'
import { holdingApi } from '@/api/endpoints'
import { useQuoteSocketStore } from '@/stores/quoteSocket'
import type { HoldingSummary, HoldingView } from '@/api/types'
import { formatNumber } from '@/utils/format'

/**
 * 我的持仓。
 *
 * 计算口径全部在后端（加权平均成本、浮动盈亏、当日盈亏），
 * 前端只做展示——避免出现「前端算一套、后端算一套，两个数字对不上」
 * 这种最常见也最难解释的不一致。
 *
 * 两处必须如实展示的数据来源说明：
 *   1. 当日盈亏依赖前收盘价，降级数据源可能给不出，此时为 null 而非 0；
 *   2. 组合总览里的 dayPnl 可能小于各持仓 dayPnl 之和
 *      （部分标的取不到前收盘价时），不能反过来当作校验条件。
 */
const router = useRouter()
const socket = useQuoteSocketStore()

const holdings = ref<HoldingView[]>([])
const summary = ref<HoldingSummary | null>(null)
const loading = ref(false)

const tradeVisible = ref(false)
const tradeMode = ref<'buy' | 'sell'>('buy')
const tradeTarget = ref<HoldingView | null>(null)

/** 用实时推送覆盖持仓行情的现价与当日涨跌。 */
function livePrice(h: HoldingView): number | null {
  return socket.quotes[h.code]?.price ?? h.currentPrice
}

/** 用实时价重算市值与浮动盈亏，让表格里的数字随推送滚动。 */
function liveMarketValue(h: HoldingView): number | null {
  const price = livePrice(h)
  return price === null ? h.marketValue : price * h.shares
}

function livePnl(h: HoldingView): number | null {
  const mv = liveMarketValue(h)
  if (mv === null || h.costTotal === null) return h.pnl
  return mv - h.costTotal
}

function livePnlPct(h: HoldingView): number | null {
  const pnl = livePnl(h)
  if (pnl === null || !h.costTotal) return h.pnlPct
  return (pnl / h.costTotal) * 100
}

const totalMarketValue = computed(() => {
  if (!holdings.value.length) return summary.value?.marketValue ?? 0
  return holdings.value.reduce((sum, h) => sum + (liveMarketValue(h) ?? 0), 0)
})

let release: (() => void) | null = null

onMounted(() => void load())

async function load(): Promise<void> {
  loading.value = true
  try {
    const [list, sum] = await Promise.allSettled([holdingApi.list(), holdingApi.summary()])
    holdings.value = list.status === 'fulfilled' ? list.value : []
    summary.value = sum.status === 'fulfilled' ? sum.value : null

    release?.()
    release = holdings.value.length ? socket.acquire(holdings.value.map((h) => h.code)) : null
  } finally {
    loading.value = false
  }
}

function openTrade(mode: 'buy' | 'sell', target?: HoldingView): void {
  tradeMode.value = mode
  tradeTarget.value = target ?? null
  tradeVisible.value = true
}

async function removeHolding(h: HoldingView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 ${h.name}（${h.code}）的持仓记录？此操作不会产生卖出记录。`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await holdingApi.remove(h.code)
    socket.dropQuote(h.code)
    ElMessage.success('持仓记录已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}
</script>

<template>
  <div class="page">
    <section class="card">
      <div class="card__header">
        <span class="card__title">组合总览</span>
        <div class="actions">
          <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openTrade('buy')">记一笔买入</el-button>
        </div>
      </div>
      <div class="card__body">
        <div class="grid grid--4">
          <div class="metric">
            <div class="metric__label">持仓只数</div>
            <div class="metric__value num">{{ summary?.count ?? 0 }}</div>
          </div>
          <div class="metric">
            <div class="metric__label">总市值</div>
            <div class="metric__value num">{{ totalMarketValue.toFixed(2) }}</div>
          </div>
          <div class="metric">
            <div class="metric__label">总成本</div>
            <div class="metric__value num">{{ formatNumber(summary?.costTotal) }}</div>
          </div>
          <div class="metric">
            <div class="metric__label">浮动盈亏 / 收益率</div>
            <div class="metric__value">
              <ChangeText :value="summary?.pnl" />
              <ChangeText :value="summary?.pnlPct" suffix="%" />
            </div>
          </div>
        </div>

        <p class="muted note">
          总市值一列已按实时推送价重算（行情未连接时使用后端返回的快照值）。
          浮动盈亏与收益率的权威口径在后端，此处仅作展示参考。
        </p>
      </div>
    </section>

    <section class="card">
      <div class="card__header">
        <span class="card__title">持仓明细</span>
      </div>
      <div class="card__body">
        <el-table
          v-loading="loading"
          :data="holdings"
          size="small"
          empty-text="暂无持仓记录"
        >
          <el-table-column prop="code" label="代码" width="105">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="120">
            <template #default="{ row }">
              <el-link
                v-if="row.name"
                type="primary"
                :underline="false"
                @click="router.push(`/stock/${row.code}`)"
              >
                {{ row.name }}
              </el-link>
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column label="持股" align="right" width="90">
            <template #default="{ row }">
              <span class="num">{{ row.shares }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成本价" align="right" width="100">
            <template #default="{ row }">
              <span class="num">{{ formatNumber(row.avgCost) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="现价" align="right" width="100">
            <template #default="{ row }">
              <ChangeText :value="livePrice(row)" :show-plus="false" />
            </template>
          </el-table-column>
          <el-table-column label="市值" align="right" width="120">
            <template #default="{ row }">
              <span class="num">{{ formatNumber(liveMarketValue(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="浮动盈亏" align="right" width="120">
            <template #default="{ row }">
              <ChangeText :value="livePnl(row)" />
            </template>
          </el-table-column>
          <el-table-column label="收益率" align="right" width="100">
            <template #default="{ row }">
              <ChangeText :value="livePnlPct(row)" suffix="%" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openTrade('buy', row)">买入</el-button>
              <el-button link type="primary" @click="openTrade('sell', row)">卖出</el-button>
              <el-button link type="danger" :icon="Delete" @click="removeHolding(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <p class="muted note">
          「删除」只清掉持仓记录，不会生成卖出流水、也不计算已实现盈亏；
          要记录实际卖出请用「卖出」。
        </p>
      </div>
    </section>

    <TradeDialog
      v-model="tradeVisible"
      :mode="tradeMode"
      :code="tradeTarget?.code ?? ''"
      :name="tradeTarget?.name"
      @done="load"
    />
  </div>
</template>

<style scoped>
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
  font-size: 18px;
  font-weight: 600;
  margin-top: 2px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.actions {
  display: flex;
  gap: 8px;
}

.note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
}
</style>

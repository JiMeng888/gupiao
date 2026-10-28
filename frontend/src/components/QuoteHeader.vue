<script setup lang="ts">
import { computed } from 'vue'
import { Star, MagicStick, Wallet } from '@element-plus/icons-vue'
import ChangeText from '@/components/ChangeText.vue'
import { formatEpochSeconds } from '@/api/types'
import type { FundamentalOverview, QuoteSnapshot } from '@/api/types'
import { formatMoney, formatNumber, formatVolume } from '@/utils/format'

/**
 * 报价头部（任务书 M1）。
 *
 * <h2>为什么延迟标注是这个组件的一部分，而不是放在页面别处</h2>
 * 合规要求是「使用非实时行情时，<b>必须在价格附近</b>明示延迟时长」。
 * 「价格附近」是硬性位置约束——把它做成页脚说明或者设置页里的文字都不满足要求。
 * 因此它必须和价格在同一个视觉单元里，这也是这里把它和现价放在同一行的原因。
 *
 * <h2>「价格变化 500ms 内不得闪烁重排」怎么保证</h2>
 * 三个措施：
 *   1. 价格区用 {@code tabular-nums} 等宽数字（全局 {@code .num} 类），
 *      数字跳动时字符宽度不变，不会推动后面的元素；
 *   2. 容器用 {@code align-items: baseline}，不因字体高度变化而错位；
 *   3. 只在数值真的变化时才更新 DOM——由 Vue 的响应式 diff 保证，
 *      本组件不做定时重绘（定时重绘是闪烁的主要来源）。
 */
const props = defineProps<{
  code: string
  quote: QuoteSnapshot | null
  /** 基本面概览，用于取市盈率 TTM；未加载时为 null */
  overview?: FundamentalOverview | null
  /** 是否有 WebSocket 实时推送接入 */
  live?: boolean
}>()

const emit = defineEmits<{
  (e: 'add-watchlist'): void
  (e: 'trade', mode: 'buy' | 'sell'): void
  (e: 'analysis'): void
}>()

/**
 * 延迟声明。
 *
 * <p>它来自后端的 {@code quote.delayMinutes}，是<b>数据源声明值</b>：
 * 0 表示上游声明为实时。前端不做任何推断或改写——
 * 「是不是延迟行情」只有供给方知道，前端猜了就是编造。
 */
/** 延迟时长只有 REST 报价带；推送帧没有该字段，此时按 0（未声明）处理。 */
const delayMinutes = computed(() => {
  const q = props.quote
  return q && 'delayMinutes' in q ? (q.delayMinutes ?? 0) : 0
})

/** 快照是否已明显过期（超过 15 分钟）。与「声明延迟」是两件事，都要提示。 */
const stale = computed(() => {
  const ts = props.quote?.ts
  if (!ts) return false
  return Date.now() / 1000 - ts > 900
})

const STATS = computed(() => {
  const q = props.quote
  return [
    { label: '今开', value: formatNumber(q?.open) },
    { label: '最高', value: formatNumber(q?.high), tone: 'up' as const },
    { label: '最低', value: formatNumber(q?.low), tone: 'down' as const },
    { label: '昨收', value: formatNumber(q?.prevClose) },
    { label: '换手率', value: formatNumber(q?.turnover) + '%' },
    { label: '成交量', value: formatVolume(q?.volume) },
    { label: '成交额', value: formatMoney(q?.amount) },
  ]
})

const peTtm = computed(() => props.overview?.peTtm ?? null)

/** 总市值同样只由 REST 报价提供，推送帧取不到时显示 {@code --}。 */
const marketCap = computed(() => {
  const q = props.quote
  return q && 'marketCap' in q ? (q.marketCap ?? null) : null
})
</script>

<template>
  <section class="card quote-head">
    <div class="quote-head__top">
      <div>
        <div class="quote-head__title">
          <h2>{{ quote?.name ?? '--' }}</h2>
          <span class="mono muted">{{ code }}</span>

          <!--
            行情性质标注。三种状态互斥，优先级从高到低：

              1. 延迟行情（delayMinutes > 0）——数据源明确声明了延迟时长，
                 按合规要求必须原样展示，且挂在价格旁。
              2. 数据陈旧（stale）——上游声明实时，但快照时间已超过 15 分钟，
                 说明数据源可能已经停止更新，如实提示。
              3. 实时（live）——有 WebSocket 推送在更新。

            三者都不成立时不显示徽标，而不是默认显示「实时」——
            没有依据时声称「实时」也是一种误导。
          -->
          <el-tag v-if="delayMinutes > 0" type="warning" size="small" effect="plain">
            延迟 {{ delayMinutes }} 分钟
          </el-tag>
          <el-tag v-else-if="stale" type="info" size="small" effect="plain">
            数据可能已过期
          </el-tag>
          <el-tag v-else-if="live" type="success" size="small" effect="plain">实时</el-tag>
        </div>

        <div class="quote-head__price">
          <ChangeText :value="quote?.price" :show-plus="false" :digits="2" class="big" />
          <ChangeText :value="quote?.change" />
          <ChangeText :value="quote?.changePct" suffix="%" />
          <span class="muted quote-head__ts">
            更新于 {{ formatEpochSeconds(quote?.ts) }}
          </span>
        </div>
      </div>

      <div class="quote-head__actions">
        <el-button :icon="Star" @click="emit('add-watchlist')">加自选</el-button>
        <el-button :icon="Wallet" @click="emit('trade', 'buy')">记买入</el-button>
        <el-button :icon="Wallet" @click="emit('trade', 'sell')">记卖出</el-button>
        <el-button type="primary" :icon="MagicStick" @click="emit('analysis')">
          AI 研判
        </el-button>
      </div>
    </div>

    <!-- 任务书要求的两项头部指标：总市值 + 市盈率 TTM。
         取不到就显示 --，不用 0 或占位数字冒充 -->
    <div class="quote-head__metrics">
      <div class="metric metric--wide">
        <div class="metric__label">总市值</div>
        <div class="metric__value num">{{ formatMoney(marketCap) }}</div>
      </div>
      <div class="metric metric--wide">
        <div class="metric__label">市盈率 TTM</div>
        <div class="metric__value num">
          {{ peTtm === null ? '--' : formatNumber(peTtm) }}
        </div>
      </div>
      <div class="quote-head__stats">
        <div v-for="s in STATS" :key="s.label">
          <span class="muted">{{ s.label }}</span>
          <span class="num">{{ s.value }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.quote-head__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 16px 0;
}

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

/*
  价格区不闪烁重排的关键两行：
    - tabular-nums：数字等宽，跳动时字符占位不变；
    - baseline 对齐：涨跌幅字号小，用基线对齐才不会上下错位。
*/
.quote-head__price :deep(.num) {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
}

.quote-head__price :deep(.big) {
  font-size: 30px;
  font-weight: 700;
}

.quote-head__ts {
  font-size: 12px;
}

.quote-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 16px 12px;
}

.quote-head__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 0 16px 12px;
}

.quote-head__stats {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 8px 16px;
  font-size: 13px;
  border-top: 1px solid var(--color-border);
  padding: 12px 16px;
}

.quote-head__stats > div {
  display: flex;
  flex-direction: column;
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
</style>

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { quoteApi } from '@/api/endpoints'
import type { MarketSourceStatus } from '@/api/types'

/**
 * 行情数据源状态。
 *
 * 存在的唯一理由是：当后端降级到内置演示数据时，界面必须如实告诉用户，
 * 否则用户会把编造的价格当成真实行情来做决策。
 *
 * 判定规则（三个状态互斥，按优先级）：
 *   1. degraded  → 有操作在用降级源，展示警示样式 + notice
 *   2. !ready    → 主数据源未就绪，行情可能不完整
 *   3. 其它      → 展示 displayName
 *
 * 注意不要把 displayName 单独当「当前数据来源」用：后端在降级时
 * 也会返回「东方财富（真实行情）」这个名字，它描述的是「配置的主数据源」，
 * 不是本次数据的来源。
 */
export const useMarketSourceStore = defineStore('marketSource', () => {
  const status = ref<MarketSourceStatus | null>(null)
  const loading = ref(false)
  const lastError = ref<string | null>(null)

  const degraded = computed(() => status.value?.degraded ?? false)
  const ready = computed(() => status.value?.ready ?? false)

  /** 顶栏徽标的文字。 */
  const badgeText = computed(() => {
    if (!status.value) return '数据源未知'
    if (status.value.degraded) return '演示数据'
    if (!status.value.ready) return '数据源未就绪'
    return status.value.displayName
  })

  /** 顶栏徽标的类型，直接喂给 el-tag。 */
  const badgeType = computed<'danger' | 'warning' | 'success' | 'info'>(() => {
    if (!status.value) return 'info'
    if (status.value.degraded) return 'danger'
    if (!status.value.ready) return 'warning'
    return 'success'
  })

  /** 需要向用户展示的警示文案；无警示时为 null。 */
  const warning = computed(() => {
    if (!status.value) return null
    return status.value.degraded || !status.value.ready ? status.value.notice : null
  })

  /** 哪些操作正在用降级数据源，用于设置页定位。 */
  const degradedActions = computed(() =>
    Object.entries(status.value?.servingByAction ?? {})
      .filter(([, source]) => source !== status.value?.provider)
      .map(([action, source]) => ({ action, source })),
  )

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      status.value = await quoteApi.source()
      lastError.value = null
    } catch (e) {
      lastError.value = e instanceof Error ? e.message : '获取数据源状态失败'
    } finally {
      loading.value = false
    }
  }

  return {
    status,
    loading,
    lastError,
    degraded,
    ready,
    badgeText,
    badgeType,
    warning,
    degradedActions,
    refresh,
  }
})

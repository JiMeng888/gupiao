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
 * 判定规则（按优先级）：
 *   1. 状态端点本身取不到 → 后端不可达，**也必须提示**（见下）
 *   2. degraded  → 有操作在用降级源，展示警示样式 + notice
 *   3. !ready    → 主数据源未就绪，行情可能不完整
 *   4. 其它      → 展示 displayName
 *
 * <h2>为什么第 1 条必须单独处理</h2>
 * 早期实现写成 `if (!status) return null`，于是出现了一个正好相反的缺陷：
 * `warning` 依赖 `status` 非空，而**后端整体不可用时 `/quote/source` 自己也请求失败**，
 * `status` 一直保持 `null`，横幅<b>完全不显示</b>。
 * 也就是说，恰恰在最需要提示用户的场合（后端挂了、页面上还留着上次的数据），
 * 提示消失了。现在改为：只要状态端点取不到（`lastError` 非空）就给出警示。
 *
 * 注意仍要区分「还没加载」与「加载失败」：刚进应用、请求在途时
 * `status === null && lastError === null`，此时不提示，避免首屏闪一条假警告。
 *
 * 另外不要把 displayName 单独当「当前数据来源」用：后端在降级时
 * 也会返回「东方财富（真实行情）」这个名字，它描述的是「配置的主数据源」，
 * 不是本次数据的来源。
 */
export const useMarketSourceStore = defineStore('marketSource', () => {
  const status = ref<MarketSourceStatus | null>(null)
  const loading = ref(false)
  const lastError = ref<string | null>(null)

  const degraded = computed(() => status.value?.degraded ?? false)
  const ready = computed(() => status.value?.ready ?? false)

  /** 状态端点本身是否取不到——即后端整体不可达 / 网络中断。 */
  const unreachable = computed(() => lastError.value !== null)

  /** 顶栏徽标的文字。 */
  const badgeText = computed(() => {
    if (unreachable.value) return '数据源不可用'
    if (!status.value) return '数据源未知'
    if (status.value.degraded) return '演示数据'
    if (!status.value.ready) return '数据源未就绪'
    return status.value.displayName
  })

  /** 顶栏徽标的类型，直接喂给 el-tag。 */
  const badgeType = computed<'danger' | 'warning' | 'success' | 'info'>(() => {
    if (unreachable.value) return 'danger'
    if (!status.value) return 'info'
    if (status.value.degraded) return 'danger'
    if (!status.value.ready) return 'warning'
    return 'success'
  })

  /** 警示标题；无警示时为 null。 */
  const warningTitle = computed(() => {
    if (unreachable.value) return '无法连接后端服务'
    if (status.value?.degraded) return '当前行情包含演示数据'
    if (status.value && !status.value.ready) return '行情数据源未就绪'
    return null
  })

  /** 警示样式：演示数据与后端不可达都是「不能据此投资」的量级。 */
  const warningType = computed<'error' | 'warning'>(() =>
    unreachable.value || degraded.value ? 'error' : 'warning',
  )

  /**
   * 需要向用户展示的警示详情；无警示时为 null。
   *
   * 三种来源的文案必须各自独立，不能互相顶替：
   * `lastError` 说「连不上」、`degraded` 说「数据是编的」、
   * `!ready` 说「数据可能不全」——用户据此采取的行动完全不同。
   */
  const warning = computed(() => {
    if (unreachable.value) {
      return `${lastError.value}。当前无法确认页面上行情的真实性与时效性，请勿据此做出投资决策。`
    }
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
    unreachable,
    badgeText,
    badgeType,
    warning,
    warningTitle,
    warningType,
    degradedActions,
    refresh,
  }
})

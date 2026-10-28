import type { MarketSourceStatus } from '@/api/types'

/**
 * 行情数据源标识 → 展示名。
 *
 * <p>抽成公共模块而不是散在各组件里：本文件的映射会随「接入新源」变化
 * （新浪就是 2026-10-21 为榜单加的），散落三份时必然出现
 * 「板块面板写着腾讯、首页写着 tencent」这种同一件事两种说法。
 *
 * <p>未知源<b>原样返回 key</b>而不是折成「未知」：显示 `kimi` 至少能让人
 * 顺着名字去查配置，显示「未知」则什么线索都没有。
 */
const PROVIDER_LABELS: Record<string, string> = {
  eastmoney: '东方财富',
  tencent: '腾讯',
  sina: '新浪',
  tushare: 'Tushare',
  mock: '内置演示数据',
}

export function providerLabel(provider: string | null | undefined): string | null {
  if (!provider) return null
  return PROVIDER_LABELS[provider] ?? provider
}

/**
 * 某个操作当前由谁服务（`servingByAction[action]`）。
 *
 * <p>为什么必须按操作读，而不是用顶部「主数据源」：降级是<b>逐次调用</b>
 * 发生的，而且榜单/板块这类操作有<b>自己的优先链</b>。
 * 用顶栏的 provider 去解释某一块数据，等于用全局状态解释局部行为。
 */
export function servingProvider(
  status: MarketSourceStatus | null | undefined,
  action: string,
): string | null {
  if (!status || !status.servingByAction) return null
  return status.servingByAction[action] ?? null
}

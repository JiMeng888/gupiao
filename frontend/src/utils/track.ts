/**
 * 埋点。
 *
 * <h2>为什么先打 console 而不是直接发请求</h2>
 * 埋点链路的价值在于「事后能查到当时发生了什么」，它不需要实时。
 * 在项目还处在「自己用 / 面试演示」阶段时，为每个交互发一条网络请求
 * 既增加后端负担，又会在弱网下干扰主流程（请求队列被埋点占满）。
 *
 * <p>因此这里保留一个统一的出口：现在打到 console，
 * 接入后端时只需替换 {@link emit} 的实现，调用方一行都不用改。
 *
 * <h2>命名约定</h2>
 * 事件名统一 {@code 模块.对象.动作}，如 {@code quote.period.change}。
 * 好处是在日志里按前缀过滤，一眼能定位是哪个模块的哪类行为。
 */

/** 埋点事件名。 */
export type TrackEvent =
  /** 周期切换（分时 / 5日 / 日K …）。 */
  | 'quote.period.change'
  /** 指标切换（MACD / RSI / BOLL / KDJ）。 */
  | 'quote.indicator.change'
  /** AI 提问。 */
  | 'analysis.question.ask'
  /** AI 报告重新生成。 */
  | 'analysis.report.regenerate'
  /** 个股页 AI 解读卡：点「生成 / 重新生成」。 */
  | 'analysis.card.generate'
  /** 个股页 AI 解读卡：点「查看完整报告」跳分析页。 */
  | 'analysis.card.open'
  /** 加入自选。 */
  | 'watchlist.item.add'
  /** 板块榜单：切换展示条数。 */
  | 'market.sector.limit.change'
  /** 板块榜单：点击刷新 / 重试。 */
  | 'market.sector.retry'
  /** 板块榜单：从领涨股跳到个股页。 */
  | 'market.sector.leader.open'
  /** 首页今日涨幅榜：失败后点重试。 */
  | 'home.movers.retry'

/** 埋点载荷：只放标量，避免把响应式对象整个序列化进去。 */
export type TrackPayload = Record<string, string | number | boolean | null | undefined>

/** 是否处于开发环境：开发环境输出到 console，生产环境静默（等接入后端）。 */
const isDev = import.meta.env.DEV

/**
 * 统一出口。
 *
 * <p>刻意不含任何业务逻辑与网络调用——这样它永远不会因为
 * 「埋点接口挂了」而影响页面功能。
 */
function emit(event: TrackEvent, payload: TrackPayload = {}): void {
  if (!isDev) {
    // 生产环境：保留钩子，接入后端时在这里发请求（如 navigator.sendBeacon）
    return
  }
  console.debug(`[track] ${event}`, payload)
}

/** 记录一次埋点。失败静默——埋点绝不能影响主流程。 */
export function track(event: TrackEvent, payload: TrackPayload = {}): void {
  try {
    emit(event, payload)
  } catch {
    /* 忽略 */
  }
}

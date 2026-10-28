/**
 * 与后端接口一一对应的类型定义。
 *
 * <p><b>本文件的字段名与可空性来自真实响应，不是照着设计稿猜的。</b>
 * 每个类型下方都标注了它对应的后端接口，改动后端 DTO 时请同步这里。
 *
 * <p>关于时间戳：<b>全站时间戳统一为 epoch 秒</b>（后端 WsProtocol 类注释里
 * 也写明了这一约定）。它们的类型是 number，不是 string。
 * 唯一的例外是 AI 报告的 {@link AnalysisReport.generatedAt}，
 * 它是带时区偏移的 ISO-8601 字符串——因为它面向人阅读，需要明确时区。
 * 展示时一律走 {@link formatEpochSeconds}，不要写 `new Date(ts)`——
 * 那会把秒当成毫秒，得到 1970 年的日期。
 */

// ---------------------------------------------------------------------------
// 通用响应包装
// ---------------------------------------------------------------------------

/**
 * 后端统一响应体。
 *
 * 注意 `code` 是业务错误码字符串（如 `AUTH_EXPIRED`），不是 HTTP 状态码；
 * HTTP 状态码由 axios 层负责区分。两者都要看：同一个 401 可能是
 * `AUTH_EXPIRED`（可静默刷新）也可能是 `AUTH_INVALID`（必须重新登录）。
 */
export interface ApiResult<T> {
  code: string
  message: string
  data: T
}

// ---------------------------------------------------------------------------
// 鉴权
// ---------------------------------------------------------------------------

/** `POST /api/v1/auth/register`、`/login`、`/refresh` 的响应。 */
export interface TokenPair {
  accessToken: string
  refreshToken: string
  tokenType: string
  /** Access Token 有效期，单位秒。前端据此决定提前多久刷新。 */
  expiresIn: number
}

/** `GET /api/v1/auth/me` */
export interface UserProfile {
  id: string
  phone: string
  nickname: string
}

// ---------------------------------------------------------------------------
// 行情
// ---------------------------------------------------------------------------

export type Exchange = 'SH' | 'SZ' | 'BJ' | string

/** `GET /api/v1/quote/{code}` */
export interface Quote {
  code: string
  name: string
  exchange: Exchange
  price: number
  change: number
  changePct: number
  open: number
  high: number
  low: number
  prevClose: number
  volume: number
  turnover: number
  amount: number
  /** epoch 秒 */
  ts: number
  /** 总市值（元）。数据源未提供时为 null，前端应渲染 `--` 而不是 0。 */
  marketCap?: number | null
  /**
   * 行情延迟分钟数（数据源声明值），0 表示实时。
   *
   * 合规要求：非实时行情必须在价格附近明示延迟时长，
   * 因此这个值大于 0 时前端必须渲染「延迟 N 分钟」标注。
   */
  delayMinutes?: number
}

/** `GET /api/v1/quote/indices` */
export interface IndexSnapshot {
  code: string
  name: string
  value: number
  change: number
  changePct: number
}

/** `GET /api/v1/quote/{code}/bars` —— amount 单位元，volume 单位手。 */
export interface Bar {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  amount: number
  prevClose: number
}

/**
 * `GET /api/v1/quote/movers` —— 市场榜单条目。
 *
 * amount 单位为元，展示时通常换算成「亿」（1e8）。
 */
export interface Mover {
  code: string
  name: string
  price: number
  change: number
  changePct: number
  amount: number
}

/**
 * `GET /api/v1/quote/sectors` —— 板块榜单条目。
 *
 * <p>涨跌幅是板块的聚合结果，涨跌家数是对它的自校验：
 * 一个 +3% 但下跌家数远多于上涨家数的板块，说明内部严重分化。
 */
export interface Sector {
  code: string
  name: string
  changePct: number
  /** 成分股上涨家数；上游缺失时为 null */
  upCount?: number | null
  /** 成分股下跌家数；上游缺失时为 null */
  downCount?: number | null
  /** 领涨股名称；上游缺失时为 null */
  leaderName?: string | null
  /** 领涨股 6 位代码；上游缺失时为 null。有它才能从板块跳到个股 */
  leaderCode?: string | null
  /** 领涨股涨跌幅（百分比数值）；上游缺失时为 null */
  leaderPct?: number | null
}

/** `GET /api/v1/quote/carousel` */
export interface CarouselItem {
  code: string
  name: string
  price: number
  change: number
  changePct: number
  highlight: string
}

/** `GET /api/v1/quote/search` */
export interface StockBrief {
  code: string
  name: string
  exchange: Exchange
}

/**
 * `GET /api/v1/quote/source`
 *
 * 关键字段是 {@link degraded}，不是 {@link displayName}。
 * 之前的后端版本只返回 provider/ready/displayName，出现过
 * 「displayName 写着『东方财富（真实行情）』但价格其实是模拟值」的情况。
 * 前端必须按下面的规则渲染，不能单独拿 displayName 当数据来源：
 *
 * - `degraded === true` → 警示样式 + 展示 notice
 * - `degraded === false && ready === false` → 提示「数据源未就绪」
 * - `degraded === false && ready === true` → 可放心展示 displayName
 */
export interface MarketSourceStatus {
  provider: string
  ready: boolean
  displayName: string
  degraded: boolean
  /** 各操作当前实际服务源，如 `{ getQuote: 'mock', getBars: 'eastmoney' }` */
  servingByAction: Record<string, string>
  notice: string | null
}

// ---------------------------------------------------------------------------
// 技术指标
// ---------------------------------------------------------------------------

export interface MacdSeries {
  dif: number[]
  dea: number[]
  macd: number[]
}

export interface BollSeries {
  upper: number[]
  mid: number[]
  lower: number[]
}

export interface KdjSeries {
  k: number[]
  d: number[]
  j: number[]
}

/** 均线组。后端一次返回 4 条，前端不必自己再算一遍。 */
export interface MaSeries {
  ma5: number[]
  ma10: number[]
  ma20: number[]
  ma60: number[]
}

/** 后端支持的五种指标名，与后端 IndicatorName 枚举一一对应。 */
export type IndicatorKey = 'macd' | 'rsi' | 'boll' | 'kdj' | 'ma'

/**
 * `GET /api/v1/indicator/{code}`
 *
 * 所有指标段都是可选的：只请求 MACD 时后端不会返回 rsi/boll/kdj 字段。
 * 渲染前必须先判空，不要假设四个都在。
 */
export interface IndicatorResponse {
  macd?: MacdSeries
  rsi?: number[]
  boll?: BollSeries
  kdj?: KdjSeries
  ma?: MaSeries
  /** 后端给出的文字研判，如「MACD 金叉」「均线多头排列」 */
  signals?: Partial<Record<IndicatorKey, string>>
}

// ---------------------------------------------------------------------------
// 基本面
// ---------------------------------------------------------------------------

/**
 * `GET /api/v1/fundamental/{code}`
 *
 * {@link dataSource} 必须展示给用户：真实接口与估算值混在同一组字段里，
 * 不标注就等于默认用户以为都是真实财报数据。
 */
export interface FundamentalOverview {
  code: string
  name: string
  pe: number | null
  peStatic: number | null
  peTtm: number | null
  pb: number | null
  roe: number | null
  grossMargin: number | null
  /** 毛利率对该行业是否适用（银行、券商等无「毛利率」概念） */
  grossMarginApplicable: boolean
  netMargin: number | null
  debtRatio: number | null
  dataSource: string
  notice: string | null
}

/** `GET /api/v1/fundamental/{code}/dupont` —— 杜邦分解，三项相乘应精确等于 roe。 */
export interface DupontAnalysis {
  code: string
  name: string
  roe: number | null
  netMargin: number | null
  assetTurnover: number | null
  equityMultiplier: number | null
  interpretation: string
  dataSource: string
}

export interface StatementPeriod {
  reportDate: string
  periodLabel: string
  quarter: string
  revenue: number | null
  netProfit: number | null
  roe: number | null
  revenueYoy: number | null
  netProfitYoy: number | null
  grossMargin: number | null
  bps: number | null
  eps: number | null
  cashflowPerShare: number | null
  dividendPlan: string | null
}

/** `GET /api/v1/fundamental/{code}/statements` */
export interface StatementsResponse {
  code: string
  name: string
  industry: string
  dataSource: string
  periods: StatementPeriod[]
  notice: string | null
}

// ---------------------------------------------------------------------------
// 资讯
// ---------------------------------------------------------------------------

/** `GET /api/v1/news/channels` */
export interface NewsChannelView {
  id: string
  name: string
  tag: string
}

/**
 * `GET /api/v1/news/list`
 *
 * {@link url} 与 {@link title} 都来自第三方上游，属于不可信内容。
 * 渲染时用 `textContent`/`{{ }}`，不要拼 `v-html`。
 */
export interface NewsItem {
  id: string
  title: string
  url: string
  intro: string
  source: string
  tags: string[]
  /** epoch 秒 */
  ctime: number
  timeText: string
  channel: string
}

// ---------------------------------------------------------------------------
// 自选股
// ---------------------------------------------------------------------------

export interface WatchlistGroup {
  id: string
  name: string
  sortOrder: number
  parentId: string | null
  itemCount: number
}

/** `GET /api/v1/watchlist/items` —— 带实时行情，行情取不到时价为 null。 */
export interface WatchlistItem {
  id: string
  code: string
  name: string
  groupId: string
  sortOrder: number
  createdAt: number
  price: number | null
  change: number | null
  changePct: number | null
}

// ---------------------------------------------------------------------------
// 持仓
// ---------------------------------------------------------------------------

/**
 * 持仓视图。
 *
 * 同一个类型在两种响应里语义不同，这是后端有意设计（见后端 HoldingView 注释）：
 *
 * - <b>买入 / 卖出响应</b>：`currentPrice` 是<b>本次成交价</b>，
 *   `pnl`/`pnlPct` 按成交价算；卖出时 `realizedPnl`/`soldShares` 为本次已实现结果。
 * - <b>查询响应</b>：`currentPrice` 是<b>最新市价</b>，`pnl` 是浮动盈亏；
 *   `realizedPnl`/`soldShares` 恒为 null。
 *
 * 另外查询响应里 `dayPnl` 可能为 null（需要前收盘价才能算当日盈亏，
 * 降级数据源未必提供），不要假设它一定有值。
 */
export interface HoldingView {
  id: string
  code: string
  name: string
  shares: number
  avgCost: number
  buyDate: string | null
  createdAt: number
  updatedAt: number
  currentPrice: number | null
  marketValue: number | null
  costTotal: number | null
  pnl: number | null
  pnlPct: number | null
  dayPnl: number | null
  realizedPnl: number | null
  soldShares: number | null
}

/** `GET /api/v1/holdings/summary` */
export interface HoldingSummary {
  count: number
  marketValue: number
  costTotal: number
  pnl: number
  pnlPct: number
  dayPnl: number
}

// ---------------------------------------------------------------------------
// AI 分析
// ---------------------------------------------------------------------------

/**
 * `GET|POST /api/v1/analysis/{code}`
 *
 * 两个字段必须展示，否则用户会把规则引擎的输出当成大模型结论：
 * - {@link model}：值为 `'rule-based'` 表示<b>没有</b>调用大模型，
 *   是按真实数据机械推导的降级报告。
 * - {@link notice}：未配置 Key 等降级原因说明。
 *
 * {@link fromArchive} 为 true 表示这是数据库里的历史结论而非刚刚重新生成，
 * 展示时应带上生成时间，避免用户拿几天前的结论当实时判断。
 */
export interface AnalysisReport {
  code: string
  name: string
  /** ISO-8601 带偏移，例如 `2026-09-17T17:30:00+08:00` */
  generatedAt: string
  thesis: string
  bullCase: string[]
  bearCase: string[]
  risks: string[]
  valuationNote: string
  actionHint: string
  model: string
  fromArchive: boolean
  dataBasis: string[]
  notice: string | null
}

/**
 * `POST /api/v1/analysis/{code}/async` 的响应。
 *
 * 请求线程只拿到一个 taskId 就结束了，真正耗时的大模型调用在后台进行。
 * 前端据此轮询 {@link AnalysisTaskView} 直到终态。
 */
export interface AnalysisTaskSubmission {
  taskId: string
  /** 提交时的初始状态，恒为 `PENDING` */
  status: AnalysisTaskStatus
}

/** 异步任务状态机，与后端 AnalysisTaskStatus 枚举一一对应。 */
export type AnalysisTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

/** `GET /api/v1/analysis/tasks/{taskId}` */
export interface AnalysisTaskView {
  taskId: string
  status: AnalysisTaskStatus
  /** 仅 `SUCCESS` 时非空 */
  report: AnalysisReport | null
  /** 仅 `FAILED` 时非空 */
  error: string | null
}

// ---------------------------------------------------------------------------
// WebSocket 协议（对应后端 WsProtocol）
// ---------------------------------------------------------------------------

/** 每 3 秒推送一帧的单只标的行情，字段与 REST 侧 {@link Quote} 基本一致。 */
export interface WsQuoteFrame {
  type: 'quote'
  code: string
  name: string
  price: number
  change: number
  changePct: number
  open: number
  high: number
  low: number
  prevClose: number
  volume: number
  amount: number | null
  turnover: number
  /** epoch 秒 */
  ts: number
}

/**
 * 报价快照：REST 报价与 WebSocket 推送帧的联合。
 *
 * <p>推送帧 {@link WsQuoteFrame} 少了 {@code exchange / marketCap / delayMinutes}
 * 三个字段——它只负责价格。这里用联合类型而不是「把推送帧补全成 Quote」：
 * 补全需要交易所、总市值这些信息，而推送里根本没有，
 * 填上去就是编造。缺的字段在界面上一律显示 {@code --}。
 *
 * <p>消费方要读这三个字段时，用 {@code 'marketCap' in snapshot} 收窄。
 */
export type QuoteSnapshot = Quote | WsQuoteFrame

export interface WsReadyFrame {
  type: 'ready'
  msg: string
}

export interface WsPongFrame {
  type: 'pong'
  /** epoch 秒 */
  ts: number
}

export interface WsRejectedCode {
  code: string
  reason: string
}

/**
 * 订阅回执。
 *
 * <b>注意 `codes` 是「订阅后的完整集合」，不是本次新增项。</b>
 * 发 subscribe(['600519']) 再发 subscribe(['NOTACODE'])，
 * 第二条回执里的 codes 仍是 ['600519']，被拒项只在 rejected 里。
 * 想拿增量请看 {@link accepted} —— 它在客户端侧由前后两次集合差集算出。
 */
export interface WsSubscribedFrame {
  type: 'subscribed'
  codes: string[]
  snapshots: WsQuoteFrame[] | null
  rejected: WsRejectedCode[] | null
}

export interface WsUnsubscribedFrame {
  type: 'unsubscribed'
  codes: string[]
  remaining: string[]
}

export interface WsErrorFrame {
  type: 'error'
  code?: string | null
  message: string
}

/** 上行消息。 */
export interface WsOutbound {
  action: 'subscribe' | 'unsubscribe' | 'ping'
  codes?: string[]
}

export type WsFrame =
  | WsQuoteFrame
  | WsReadyFrame
  | WsPongFrame
  | WsSubscribedFrame
  | WsUnsubscribedFrame
  | WsErrorFrame

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

/**
 * 把 epoch 秒格式化为 `MM-DD HH:mm:ss`。
 *
 * 单列一个函数是为了避免有人图省事写 `new Date(ts)`——
 * JS 的 Date 构造器把数字当毫秒，秒级时间戳会得到 1970 年。
 */
export function formatEpochSeconds(ts: number | null | undefined, withSeconds = true): string {
  if (ts === null || ts === undefined || !Number.isFinite(ts)) return '--'
  const d = new Date(ts * 1000)
  const pad = (n: number) => String(n).padStart(2, '0')
  const base = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return withSeconds ? `${base}:${pad(d.getSeconds())}` : base
}

/** 把 ISO-8601 字符串格式化为 `YYYY-MM-DD HH:mm`。 */
export function formatIsoDateTime(iso: string | null | undefined): string {
  if (!iso) return '--'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

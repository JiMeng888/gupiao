import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  WsFrame,
  WsQuoteFrame,
  WsRejectedCode,
  WsSubscribedFrame,
} from '@/api/types'

/**
 * 实时行情 WebSocket（对应后端 `/ws/quote`）。
 *
 * <h2>为什么用「引用计数」而不是「谁连谁订阅」</h2>
 * 同一个页面里可能有多处需要同一只标的：比如自选列表和详情抽屉同时开着
 * 600519。如果各自订阅/退订，先关闭的那一方会把另一方还在用的订阅退掉，
 * 表现为「关掉一个弹窗，列表就不动了」这种偶发难查的问题。
 * 这里对每只标的计数，计数归零才真正退订。
 *
 * <h2>协议里两个容易搞错的点</h2>
 * 1. 时间戳是 <b>epoch 秒</b>，不是毫秒。格式化必须走 formatEpochSeconds，
 *    直接用 `new Date(ts)` 会得到 1970 年。
 * 2. 订阅回执里的 `codes` 是<b>订阅后的完整集合</b>而不是本次新增项。
 *    所以这里直接用它覆盖本地集合，而不是往集合里追加——追加会重复。
 *
 * <h2>连接生命周期</h2>
 * 整个应用共用一个 WebSocket 连接，由本 store 持有。
 * 断线后指数退避重连（1s → 2s → 4s → … 最多 30s），
 * 重连成功后自动恢复此前的订阅集合。
 */

/** 重连退避上限。行情场景下 30 秒已经足够慢，再久不如直接提示用户手动刷新。 */
const MAX_RECONNECT_DELAY_MS = 30_000

/** 心跳间隔。后端空闲超时是 10 分钟，30 秒一次足够，也不会造成额外负担。 */
const PING_INTERVAL_MS = 30_000

/** 单连接最多订阅多少只，与后端 QuoteSessionRegistry 的上限保持一致。 */
const MAX_CODES = 50

function buildWsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // 用相对路径交给 Vite / Nginx 代理，前后端分域时不需要改这里
  return `${protocol}//${window.location.host}/ws/quote`
}

export const useQuoteSocketStore = defineStore('quoteSocket', () => {
  const connected = ref(false)
  const lastError = ref<string | null>(null)
  /** 各标的的最新一帧，按 code 索引。 */
  const quotes = ref<Record<string, WsQuoteFrame>>({})
  /** 服务端确认的订阅集合（以它的回执为准，不是我们本地记的）。 */
  const subscribedCodes = ref<string[]>([])
  /** 被服务端拒绝的代码及原因。展示时务必用文本插值，不要拼 innerHTML。 */
  const rejected = ref<WsRejectedCode[]>([])
  /** 最近一次收到行情的时间（epoch 秒），用于判断推送是否还活着。 */
  const lastFrameAt = ref<number | null>(null)

  /** 引用计数：code -> 还有几处需要它。 */
  const refCounts = new Map<string, number>()

  let socket: WebSocket | null = null
  let reconnectDelay = 1_000
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let pingTimer: ReturnType<typeof setInterval> | null = null
  /** 主动关闭时置位，避免把「用户离开页面」当成异常断线去重连。 */
  let closedByUs = false

  const codeCount = computed(() => subscribedCodes.value.length)

  /** 订阅集合是否已超出后端上限（超出部分会被服务端拒绝）。 */
  const overLimit = computed(() => codeCount.value >= MAX_CODES)

  function send(payload: unknown): void {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(payload))
    }
  }

  function handleFrame(frame: WsFrame): void {
    switch (frame.type) {
      case 'quote':
        quotes.value = { ...quotes.value, [frame.code]: frame }
        lastFrameAt.value = frame.ts
        break

      case 'subscribed': {
        const f = frame as WsSubscribedFrame
        // 覆盖而不是合并：后端给的就是完整集合
        subscribedCodes.value = f.codes ?? []
        if (f.snapshots?.length) {
          const next = { ...quotes.value }
          for (const s of f.snapshots) next[s.code] = s
          quotes.value = next
        }
        // rejected 每次都整体替换，避免同一个非法代码反复出现
        if (f.rejected?.length) {
          rejected.value = f.rejected
        }
        break
      }

      case 'unsubscribed':
        subscribedCodes.value = frame.remaining ?? []
        break

      case 'error':
        lastError.value = frame.message
        break

      case 'ready':
      case 'pong':
        break
    }
  }

  function scheduleReconnect(): void {
    if (reconnectTimer || closedByUs) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, reconnectDelay)
    // 指数退避，封顶
    reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
  }

  function connect(): void {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
      return
    }
    closedByUs = false
    const ws = new WebSocket(buildWsUrl())
    socket = ws

    ws.onopen = () => {
      connected.value = true
      lastError.value = null
      reconnectDelay = 1_000

      // 重连后恢复订阅：服务端的订阅表是跟着连接走的，断开即清空
      const codes = [...refCounts.keys()]
      if (codes.length) send({ action: 'subscribe', codes })

      pingTimer = setInterval(() => send({ action: 'ping' }), PING_INTERVAL_MS)
    }

    ws.onmessage = (ev) => {
      try {
        handleFrame(JSON.parse(ev.data as string) as WsFrame)
      } catch {
        // 单帧解析失败不应影响整条连接
      }
    }

    ws.onerror = () => {
      lastError.value = '实时行情连接异常'
    }

    ws.onclose = () => {
      connected.value = false
      if (pingTimer) {
        clearInterval(pingTimer)
        pingTimer = null
      }
      socket = null
      // 订阅集合是跟连接走的，连接没了就清空，
      // 否则界面会显示「已订阅」但收不到任何推送
      subscribedCodes.value = []
      scheduleReconnect()
    }
  }

  function disconnect(): void {
    closedByUs = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (pingTimer) {
      clearInterval(pingTimer)
      pingTimer = null
    }
    socket?.close()
    socket = null
    connected.value = false
  }

  /**
   * 声明「我现在需要这些标的的实时行情」，返回一个释放函数。
   *
   * 用法（组件里）：
   * ```ts
   * const release = socket.acquire(['600519'])
   * onUnmounted(release)
   * ```
   */
  function acquire(codes: string[]): () => void {
    connect()
    const toSubscribe: string[] = []

    for (const code of codes) {
      const current = refCounts.get(code) ?? 0
      refCounts.set(code, current + 1)
      if (current === 0) toSubscribe.push(code)
    }

    if (toSubscribe.length) send({ action: 'subscribe', codes: toSubscribe })

    return () => {
      const toUnsubscribe: string[] = []
      for (const code of codes) {
        const current = refCounts.get(code) ?? 0
        if (current <= 1) {
          refCounts.delete(code)
          toUnsubscribe.push(code)
        } else {
          refCounts.set(code, current - 1)
        }
      }
      if (toUnsubscribe.length) send({ action: 'unsubscribe', codes: toUnsubscribe })
    }
  }

  /** 清掉某一帧的本地缓存（例如该标的已从列表移除）。 */
  function dropQuote(code: string): void {
    const next = { ...quotes.value }
    delete next[code]
    quotes.value = next
  }

  return {
    connected,
    lastError,
    quotes,
    subscribedCodes,
    rejected,
    lastFrameAt,
    codeCount,
    overLimit,
    connect,
    disconnect,
    acquire,
    dropQuote,
  }
})

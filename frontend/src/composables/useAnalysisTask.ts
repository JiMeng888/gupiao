import { computed, onBeforeUnmount, ref } from 'vue'
import { analysisApi } from '@/api/endpoints'
import type { AnalysisReport, AnalysisTaskStatus } from '@/api/types'

/**
 * 异步分析任务（任务书 M3，同时对应走查 P0-1）。
 *
 * <h2>为什么必须走异步而不是「同步接口 + 长超时」</h2>
 * 一次分析要 10~40 秒。同步链路下这 40 秒会一直占着一个 HTTP 请求，
 * 而浏览器对同一域名的并发连接数有限（HTTP/1.1 通常是 6）。
 * 用户切到别的 Tab、或页面上有别的轮询（行情 WS 的 HTTP 回退），
 * 就可能被这个长请求堵住。异步链路下：
 *   - 提交是 202，几十毫秒返回；
 *   - 之后是 2 秒一次的<b>纯读库</b>小请求，随时可以取消。
 *
 * <h2>轮询节奏</h2>
 * 起始 1.2s，每次乘 1.25，上限 4s，总预算 90s。
 * <ul>
 *   <li>不用固定 2s：多数任务 10s 内完成，固定 2s 会让首屏结论
 *       平均多等 1 秒；退避让「快任务更快、慢任务更省」；</li>
 *   <li>上限 4s：再慢用户就会以为卡死了；</li>
 *   <li>总预算 90s：后端 llm.timeout 是 45s，留一倍余量；
 *       超时后<b>不宣告失败</b>——后端可能仍在跑，报告稍后会入库，
 *       此时提示「仍在生成中，可稍后返回查看」比「失败」更准确。</li>
 * </ul>
 */
const INITIAL_DELAY = 1_200
const BACKOFF = 1.25
const MAX_DELAY = 4_000
const TOTAL_BUDGET = 90_000

/** 对外可见的运行阶段。比后端状态多一个 `submitting`，用于渲染「提交中」。 */
export type AnalysisPhase = 'idle' | 'submitting' | AnalysisTaskStatus | 'timeout'

export function useAnalysisTask() {
  const phase = ref<AnalysisPhase>('idle')
  const report = ref<AnalysisReport | null>(null)
  const error = ref<string | null>(null)

  /** 累计等待毫秒数，供进度条使用。 */
  const waited = ref(0)

  let timer: ReturnType<typeof setTimeout> | null = null
  let aborted = false

  const running = computed(
    () => phase.value === 'submitting' || phase.value === 'PENDING' || phase.value === 'RUNNING',
  )

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  /** 停止轮询。切换标的或组件卸载时必须调用，否则会残留定时器写已卸载的状态。 */
  function cancel(): void {
    aborted = true
    clearTimer()
  }

  function schedule(delay: number, fn: () => void): void {
    clearTimer()
    timer = setTimeout(fn, delay)
  }

  /**
   * 跑一次分析。
   *
   * <p>先读存档（{@code force=false} 时）：有存档就直接返回，
   * 省一次大模型调用，也省用户等待；没有才提交异步任务。
   *
   * @param force 为 true 时跳过存档直接重新生成
   */
  async function run(code: string, force = false): Promise<void> {
    cancel()
    aborted = false
    waited.value = 0
    error.value = null
    report.value = null

    if (!force) {
      try {
        const cached = await analysisApi.get(code, 8_000)
        if (aborted) return
        if (cached) {
          report.value = cached
          phase.value = 'SUCCESS'
          return
        }
      } catch {
        // 读存档失败（超时/无存档）不算错误：继续走异步生成。
        // 这里刻意不写 error，避免把「还没有存档」显示成「获取失败」。
      }
    }

    phase.value = 'submitting'
    let taskId: string
    try {
      const submission = await analysisApi.submit(code)
      if (aborted) return
      taskId = submission.taskId
    } catch (e) {
      if (aborted) return
      phase.value = 'FAILED'
      error.value = e instanceof Error ? e.message : '提交分析任务失败'
      return
    }

    await poll(taskId)
  }

  /**
   * 轮询到终态。
   *
   * <p>只按 taskId 查库，不触发模型调用，因此每次请求都很轻，
   * 可以放心地用相对密集的节奏去问。
   */
  async function poll(taskId: string): Promise<void> {
    let delay = INITIAL_DELAY
    const started = Date.now()

    const tick = async (): Promise<void> => {
      if (aborted) return

      let view
      try {
        view = await analysisApi.task(taskId)
      } catch (e) {
        if (aborted) return
        // 404 = taskId 已失效（过期被清理），重试没有意义，直接停下
        const code0 = (e as { code?: string }).code
        if (code0 === 'NOT_FOUND' || code0 === 'STOCK_NOT_FOUND') {
          phase.value = 'FAILED'
          error.value = '分析任务已失效，请重新生成'
          return
        }
        // 其他错误（网络抖动）：按退避重试，直到耗尽预算
      }

      if (view) {
        if (aborted) return
        phase.value = view.status
        if (view.status === 'SUCCESS') {
          report.value = view.report
          return
        }
        if (view.status === 'FAILED') {
          error.value = view.error ?? '分析生成失败'
          return
        }
      }

      waited.value = Date.now() - started
      if (waited.value >= TOTAL_BUDGET) {
        // 后端还在跑的可能性很大，所以不判失败
        phase.value = 'timeout'
        return
      }

      delay = Math.min(delay * BACKOFF, MAX_DELAY)
      schedule(delay, () => void tick())
    }

    await tick()
  }

  onBeforeUnmount(cancel)

  return { phase, report, error, waited, running, run, cancel }
}

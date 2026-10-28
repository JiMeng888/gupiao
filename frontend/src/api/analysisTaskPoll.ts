import { analysisApi } from './endpoints'
import { BizError } from './http'
import type { AnalysisReport, AnalysisTaskStatus, AnalysisTaskView } from './types'

/**
 * 异步分析任务的轮询。
 *
 * <h2>为什么单独成一个模块</h2>
 * 「提交 taskId → 反复查询直到终态」这段逻辑有几处容易写错的地方，
 * 集中在一处才能测、也才只有一份实现：
 *
 * <ul>
 *   <li><b>难度不在于查询，而在于终止条件。</b>至少要处理四种终止：
 *       SUCCESS、FAILED、taskId 失效（404）、以及「一直 PENDING/RUNNING 到超时」。
 *       漏掉任何一种都会变成无限轮询——用户看着转圈，后台请求一直在发。</li>
 *   <li><b>不能用 setInterval。</b>固定间隔会在后端慢时堆积并发请求
 *       （上一次响应还没回来就发下一次），而且时间戳对不上。
 *       这里用「等上一次结束，再 sleep 一段」的串行循环。</li>
 *   <li><b>必须有总时长上限。</b>后端最坏是「排队 + 45 秒大模型」，
 *       没有上限时一次异常可以让轮询跑到天荒地老。</li>
 * </ul>
 *
 * <h2>间隔为什么递增</h2>
 * 前 1~2 秒内出结果是常见的（命中规则化降级路径，几乎瞬时完成），
 * 所以前几次要密；而一旦确定在等大模型，就是几十秒的量级，
 * 这时再每秒查一次只是白耗请求。退避曲线取 1s → 1.5s → 2s → 3s 后恒定 3s：
 * 既覆盖了「快路径」，也把慢路径的请求数压到十几次。
 */

/** 默认轮询间隔（毫秒）。用完后恒定取最后一个值。 */
const DEFAULT_INTERVALS = [1_000, 1_500, 2_000, 3_000] as const

/**
 * 默认总时长上限。
 *
 * 取值 180s：后端最坏情况是「线程池排队 + 45s 大模型 + 归档」，
 * 180s 留了足够余量；同时它必须显著小于前端用户的耐心上限，
 * 否则出错时用户会以为是页面卡死。
 */
const DEFAULT_TIMEOUT_MS = 180_000

/** 轮询被主动取消时抛出的业务码。调用方应静默处理，不要弹错误提示。 */
export const POLL_ABORTED = 'ANALYSIS_POLL_ABORTED'

export interface PollAnalysisTaskOptions {
  /** 轮询间隔序列，可覆盖默认值（测试用） */
  intervals?: readonly number[]
  /** 总时长上限（毫秒） */
  timeoutMs?: number
  /** 每次拿到状态后的回调，用于展示「正在生成…」这类进度 */
  onProgress?: (view: AnalysisTaskView, attempt: number) => void
  /** 取消信号；abort 后立即结束并抛 {@link POLL_ABORTED} */
  signal?: AbortSignal
  /** 注入查询实现，便于单测（默认走 analysisApi.task） */
  fetchTask?: (taskId: string) => Promise<AnalysisTaskView>
  /** 注入 sleep，便于单测用假时钟 */
  sleep?: (ms: number) => Promise<void>
}

/**
 * 轮询任务直到终态，返回报告。
 *
 * @throws BizError `ANALYSIS_FAILED` 任务失败；
 *                  `ANALYSIS_TASK_NOT_FOUND` taskId 失效；
 *                  `ANALYSIS_POLL_TIMEOUT` 超过总时长上限；
 *                  {@link POLL_ABORTED} 被取消
 */
export async function pollAnalysisTask(
  taskId: string,
  options: PollAnalysisTaskOptions = {},
): Promise<AnalysisReport> {
  const intervals = options.intervals?.length ? options.intervals : DEFAULT_INTERVALS
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const fetchTask = options.fetchTask ?? ((id: string) => analysisApi.task(id))
  const sleep = options.sleep ?? defaultSleep

  const startedAt = Date.now()
  let attempt = 0

  for (;;) {
    throwIfAborted(options.signal)
    attempt += 1

    let view: AnalysisTaskView
    try {
      view = await fetchTask(taskId)
    } catch (e) {
      // 404 是「这个 taskId 不存在」——多半是本地残留的过期 ID，
      // 或者服务端重启后任务表被清理。重试没有意义，直接抛出。
      if (isTaskMissing(e)) {
        throw new BizError('ANALYSIS_TASK_NOT_FOUND', '分析任务不存在或已过期，请重新发起', 404)
      }
      throw e
    }

    options.onProgress?.(view, attempt)

    const report = resolveReport(view)
    if (report) {
      return report
    }

    // 终态失败：后端已经把原因写在 error 里，原样透出
    if (view.status === 'FAILED') {
      throw new BizError('ANALYSIS_FAILED', view.error || '分析失败，请稍后重试')
    }

    // 仍未完成，判断是否已经超时
    const elapsed = Date.now() - startedAt
    if (elapsed >= timeoutMs) {
      throw new BizError(
        'ANALYSIS_POLL_TIMEOUT',
        `分析耗时超过 ${Math.round(timeoutMs / 1000)} 秒仍未完成，请稍后在「历史存档」中查看`,
      )
    }

    const wait = intervals[Math.min(attempt - 1, intervals.length - 1)]
    // 不超过剩余预算，避免「等待本身」把上限拉爆
    await sleep(Math.min(wait, Math.max(0, timeoutMs - elapsed)))
  }
}

/**
 * 从任务视图中取出报告。
 *
 * `SUCCESS` 却拿不到报告，说明后端报告反序列化失败
 * （`AnalysisService.task` 会把它呈现为 FAILED，但这里也兜一层），
 * 此时按失败处理而不是返回 null 让调用方去猜。
 */
function resolveReport(view: AnalysisTaskView): AnalysisReport | null {
  if (view.status !== 'SUCCESS') {
    return null
  }
  if (!view.report) {
    throw new BizError('ANALYSIS_FAILED', '报告数据损坏，请重新生成')
  }
  return view.report
}

function isTaskMissing(e: unknown): boolean {
  if (e instanceof BizError) {
    return e.status === 404 || e.code === 'ANALYSIS_TASK_NOT_FOUND'
  }
  return false
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new BizError(POLL_ABORTED, '已取消轮询')
  }
}

function defaultSleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 判断某个状态是否已到终态（供 UI 决定是否停止展示进度）。 */
export function isTerminalStatus(status: AnalysisTaskStatus): boolean {
  return status === 'SUCCESS' || status === 'FAILED'
}

import { describe, expect, it, vi } from 'vitest'
import { POLL_ABORTED, isTerminalStatus, pollAnalysisTask } from './analysisTaskPoll'
import { BizError } from './http'
import type { AnalysisReport, AnalysisTaskView } from './types'

/**
 * 轮询模块的单测。
 *
 * <p>这个模块的难点不在「查到结果」，而在<b>终止条件</b>：
 * 少了任何一个，前端就会变成无限轮询——用户看着转圈，请求一直在发，
 * 而页面既不报错也不恢复。所以这里的断言几乎都围绕「什么时候必须停下来」。
 *
 * <p>全部用注入的 `fetchTask` / `sleep`，不碰真实网络也不等真实时间：
 * 轮询的默认间隔是 1~3 秒、上限 180 秒，用真时钟测一次成功路径都要 1 秒起步，
 * 测超时路径要 3 分钟——那样的测试没人会跑。
 */

/** 构造一个只填必要字段的报告；轮询测试不关心报告内容。 */
function report(model = 'deepseek-chat'): AnalysisReport {
  return {
    code: '600519',
    name: '贵州茅台',
    generatedAt: '2026-10-16T15:00:00+08:00',
    thesis: '测试结论',
    bullCase: [],
    bearCase: [],
    risks: [],
    valuationNote: '',
    actionHint: '',
    model,
    fromArchive: false,
    dataBasis: [],
    notice: null,
  }
}

function view(status: AnalysisTaskView['status'], extra: Partial<AnalysisTaskView> = {}): AnalysisTaskView {
  return { taskId: 'task-1', status, report: null, error: null, ...extra }
}

/** 记录 sleep 的调用间隔，用于断言退避曲线；sleep 本身立即返回。 */
function fakeSleep() {
  const slept: number[] = []
  return {
    slept,
    sleep: async (ms: number) => {
      slept.push(ms)
    },
  }
}

describe('pollAnalysisTask 的终止条件', () => {
  it('第一次查询就 SUCCESS 时立即返回报告，不额外 sleep', async () => {
    const { slept, sleep } = fakeSleep()
    const fetchTask = vi.fn().mockResolvedValue(view('SUCCESS', { report: report() }))

    const result = await pollAnalysisTask('task-1', { fetchTask, sleep })

    expect(result.model).toBe('deepseek-chat')
    expect(fetchTask).toHaveBeenCalledTimes(1)
    expect(slept).toEqual([])
  })

  it('PENDING → RUNNING → SUCCESS 期间持续轮询，并在中间 sleep', async () => {
    const { slept, sleep } = fakeSleep()
    const fetchTask = vi
      .fn()
      .mockResolvedValueOnce(view('PENDING'))
      .mockResolvedValueOnce(view('RUNNING'))
      .mockResolvedValueOnce(view('SUCCESS', { report: report('qwen-plus') }))

    const progress: string[] = []
    const result = await pollAnalysisTask('task-1', {
      fetchTask,
      sleep,
      onProgress: (v) => progress.push(v.status),
    })

    expect(result.model).toBe('qwen-plus')
    expect(fetchTask).toHaveBeenCalledTimes(3)
    expect(progress).toEqual(['PENDING', 'RUNNING', 'SUCCESS'])
    // 两次等待，且间隔递增（1s → 1.5s）
    expect(slept).toEqual([1_000, 1_500])
  })

  it('FAILED 时抛出后端给的失败原因，不继续轮询', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi
      .fn()
      .mockResolvedValue(view('FAILED', { error: '未找到股票代码 999999' }))

    await expect(pollAnalysisTask('task-1', { fetchTask, sleep })).rejects.toMatchObject({
      code: 'ANALYSIS_FAILED',
      message: '未找到股票代码 999999',
    })
    expect(fetchTask).toHaveBeenCalledTimes(1)
  })

  it('FAILED 但后端没给原因时用兜底文案，不抛空消息', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi.fn().mockResolvedValue(view('FAILED', { error: null }))

    await expect(pollAnalysisTask('task-1', { fetchTask, sleep })).rejects.toMatchObject({
      code: 'ANALYSIS_FAILED',
    })
  })

  it('SUCCESS 但报告为空时按失败处理，不把 null 交给调用方', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi.fn().mockResolvedValue(view('SUCCESS', { report: null }))

    await expect(pollAnalysisTask('task-1', { fetchTask, sleep })).rejects.toMatchObject({
      code: 'ANALYSIS_FAILED',
    })
  })

  it('taskId 不存在（404）时立即失败且不重试', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi
      .fn()
      .mockRejectedValue(new BizError('ANALYSIS_TASK_NOT_FOUND', '分析任务不存在', 404))

    await expect(pollAnalysisTask('task-1', { fetchTask, sleep })).rejects.toMatchObject({
      code: 'ANALYSIS_TASK_NOT_FOUND',
    })
    expect(fetchTask).toHaveBeenCalledTimes(1)
  })

  it('一直不达终态时按总时长上限报错，不会无限轮询', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi.fn().mockResolvedValue(view('RUNNING'))

    await expect(
      pollAnalysisTask('task-1', { fetchTask, sleep, intervals: [100], timeoutMs: 50 }),
    ).rejects.toMatchObject({ code: 'ANALYSIS_POLL_TIMEOUT' })
  })

  it('已被取消时立刻抛出取消码，且一次查询都不发', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi.fn().mockResolvedValue(view('SUCCESS', { report: report() }))
    const controller = new AbortController()
    controller.abort()

    await expect(
      pollAnalysisTask('task-1', { fetchTask, sleep, signal: controller.signal }),
    ).rejects.toMatchObject({ code: POLL_ABORTED })
    expect(fetchTask).not.toHaveBeenCalled()
  })

  it('轮询中途被取消时停止查询（切换标的不会让旧任务继续打后端）', async () => {
    const { sleep } = fakeSleep()
    const controller = new AbortController()
    const fetchTask = vi.fn().mockImplementation(async () => {
      // 第一次查询后模拟用户切走
      controller.abort()
      return view('RUNNING')
    })

    await expect(
      pollAnalysisTask('task-1', { fetchTask, sleep, signal: controller.signal }),
    ).rejects.toMatchObject({ code: POLL_ABORTED })
    expect(fetchTask).toHaveBeenCalledTimes(1)
  })

  it('网络类异常（非 404）原样抛出，不被误判成任务失效', async () => {
    const { sleep } = fakeSleep()
    const fetchTask = vi.fn().mockRejectedValue(new BizError('NETWORK_ERROR', '无法连接服务器'))

    await expect(pollAnalysisTask('task-1', { fetchTask, sleep })).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
    })
  })
})

describe('isTerminalStatus', () => {
  it('只有 SUCCESS / FAILED 是终态', () => {
    expect(isTerminalStatus('SUCCESS')).toBe(true)
    expect(isTerminalStatus('FAILED')).toBe(true)
    expect(isTerminalStatus('PENDING')).toBe(false)
    expect(isTerminalStatus('RUNNING')).toBe(false)
  })
})

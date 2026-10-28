<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Refresh, Search, Clock } from '@element-plus/icons-vue'
import DisclaimerNote from '@/components/DisclaimerNote.vue'
import { analysisApi, quoteApi } from '@/api/endpoints'
import { POLL_ABORTED, pollAnalysisTask } from '@/api/analysisTaskPoll'
import { BizError } from '@/api/http'
import type { AnalysisReport, StockBrief } from '@/api/types'
import { formatIsoDateTime } from '@/api/types'

/**
 * AI 研判。
 *
 * <h2>本页面最重要的职责：让用户分清「模型结论」与「规则推导」</h2>
 * 后端在未配置大模型 Key（或调用失败）时会退化为规则引擎，
 * 此时返回的报告带两个关键字段：
 *   - `model === 'rule-based'` —— 没有调用大模型
 *   - `notice` —— 降级原因
 *
 * 两者都必须显著展示。把规则引擎的输出用「AI 智能研判」的口吻呈现，
 * 是这类产品最容易犯也最不负责任的错误。
 *
 * 另外 `fromArchive` 为 true 表示这是库里的历史结论，
 * 界面上要标明生成时间，否则用户会拿几天前的判断当实时结论。
 *
 * <h2>两个按钮走的是两条完全不同的链路（重要）</h2>
 * <table>
 *   <tr><th>按钮</th><th>链路</th><th>等待时长</th></tr>
 *   <tr><td>读取最新存档</td><td>`GET /analysis/{code}` 同步</td>
 *       <td>命中存档时瞬时；<b>无存档时会同步生成一次</b>，最长 45s</td></tr>
 *   <tr><td>重新生成</td><td>`POST /async` + 轮询</td>
 *       <td>立即返回 taskId，生成在后台线程池，页面全程可交互</td></tr>
 * </table>
 *
 * 「重新生成」必须走异步：后端为此专门隔离了一个线程池
 * （`analysisTaskThreadPool`），用同步端点会让那次调用重新占住 Tomcat 线程，
 * 异步化形同虚设；而且同步路径最长 45s，用户只能对着转圈等。
 */
const route = useRoute()
const router = useRouter()

const code = computed(() => (route.params.code as string | undefined) ?? '')
const report = ref<AnalysisReport | null>(null)
const history = ref<AnalysisReport[]>([])
const loading = ref(false)
const regenerating = ref(false)
/** 生成过程中的进度文案，仅在 regenerating 为 true 时有意义 */
const progressText = ref('')
const errorMessage = ref<string | null>(null)

/** 无 code 时的输入框 */
const keyword = ref('')
const options = ref<StockBrief[]>([])
const searching = ref(false)

const isRuleBased = computed(() => report.value?.model === 'rule-based')

const modelLabel = computed(() => {
  if (!report.value) return ''
  return isRuleBased.value
    ? `规则引擎推导（未调用大模型，model=${report.value.model}）`
    : `大模型 ${report.value.model}`
})

/**
 * 当前进行中的轮询。
 *
 * 必须持有它并在两种时机取消：切换标的、组件卸载。
 * 否则「看着 600519 的分析，中途跳到 000858」时，
 * 前一个任务的轮询返回后会把 600519 的报告渲染到 000858 的页面上——
 * 用户看到的是张冠李戴的结论，而且完全无从察觉。
 */
let pollAbort: AbortController | null = null

function cancelPoll(): void {
  pollAbort?.abort()
  pollAbort = null
}

onMounted(() => {
  if (code.value) void loadReport()
})

onUnmounted(cancelPoll)

watch(code, (v) => {
  cancelPoll()
  if (v) void loadReport()
  else {
    report.value = null
    history.value = []
  }
})

async function remoteSearch(kw: string): Promise<void> {
  const q = kw.trim()
  if (!q) {
    options.value = []
    return
  }
  searching.value = true
  try {
    options.value = await quoteApi.search(q)
  } finally {
    searching.value = false
  }
}

function pick(target: string): void {
  keyword.value = ''
  options.value = []
  router.push({ name: 'analysis-detail', params: { code: target } })
}

/**
 * 读取分析结论。
 *
 * 超时按分析预算给（见 analysisApi.get）——这个接口在无存档时
 * 会同步触发生成，用全局 20s 会在那种情况下必然失败。
 */
async function loadReport(): Promise<void> {
  loading.value = true
  errorMessage.value = null
  try {
    report.value = await analysisApi.get(code.value)
    history.value = await analysisApi.history(code.value, 10).catch(() => [])
  } catch (e) {
    report.value = null
    errorMessage.value = e instanceof Error ? e.message : '获取分析失败'
  } finally {
    loading.value = false
  }
}

/**
 * 强制重新生成：提交异步任务 → 轮询到终态。
 *
 * 与「读取最新存档」分开成两个按钮，是因为重新生成会真实消耗一次大模型调用：
 * 用户打开页面时不该默认触发它，得由用户显式决定。
 */
async function regenerate(): Promise<void> {
  // 连点两次时，前一个轮询若不取消，两个任务的结果会互相覆盖，
  // 最后留在页面上的是哪个报告取决于响应顺序（不确定）。
  cancelPoll()

  regenerating.value = true
  progressText.value = '正在提交分析任务…'

  const controller = new AbortController()
  pollAbort = controller

  try {
    const submission = await analysisApi.submit(code.value)
    progressText.value = '任务已提交，正在生成（通常 10~40 秒）…'

    const result = await pollAnalysisTask(submission.taskId, {
      signal: controller.signal,
      onProgress: (view, attempt) => {
        progressText.value =
          view.status === 'PENDING'
            ? '任务排队中…'
            : `正在生成（已查询 ${attempt} 次，通常 10~40 秒）…`
      },
    })

    report.value = result
    history.value = await analysisApi.history(code.value, 10).catch(() => [])
    ElMessage.success('已重新生成')
  } catch (e) {
    // 主动取消（切换标的 / 卸载 / 连点）不是错误，静默退出
    if (e instanceof BizError && e.code === POLL_ABORTED) {
      return
    }
    ElMessage.error(e instanceof Error ? e.message : '重新生成失败')
  } finally {
    // 只有「仍是自己这次轮询」时才清理状态：
    // 否则被取消的那次会在新任务运行时把 regenerating 置回 false
    if (pollAbort === controller) {
      pollAbort = null
      regenerating.value = false
      progressText.value = ''
    }
  }
}
</script>

<template>
  <div class="page">
    <!-- 未选择标的 -->
    <section v-if="!code" class="card">
      <div class="card__header">
        <span class="card__title">选择要研判的标的</span>
      </div>
      <div class="card__body">
        <el-select
          v-model="keyword"
          filterable
          remote
          clearable
          reserve-keyword
          placeholder="输入股票代码或名称"
          :remote-method="remoteSearch"
          :loading="searching"
          style="width: 320px"
          @change="pick"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
          <el-option
            v-for="item in options"
            :key="item.code"
            :label="`${item.name} ${item.code}`"
            :value="item.code"
          />
        </el-select>
        <p class="muted" style="margin-top: 12px; font-size: 12px">
          也可以直接在行情页选中个股后点「AI 研判」。
        </p>
      </div>
    </section>

    <template v-else>
      <section class="card">
        <div class="card__header">
          <span class="card__title">
            {{ report?.name || code }} · AI 研判
            <span class="mono muted">{{ code }}</span>
          </span>
          <div class="actions">
            <el-button
              size="small"
              :icon="Refresh"
              :loading="loading"
              :disabled="regenerating"
              @click="loadReport"
            >
              读取最新存档
            </el-button>
            <el-button
              size="small"
              type="primary"
              :icon="MagicStick"
              :loading="regenerating"
              @click="regenerate"
            >
              重新生成
            </el-button>
          </div>
        </div>

        <div class="card__body">
          <!--
            生成进度提示。异步链路下这次等待是「可中断、可离开页面」的，
            所以必须给出进度文案，而不是只放一个转圈的按钮——
            用户不知道还要等多久时，最可能的行为是连点或刷新。
          -->
          <el-alert
            v-if="regenerating"
            type="info"
            :closable="false"
            show-icon
            style="margin-bottom: 12px"
            title="正在重新生成分析结论"
            :description="progressText"
          />

          <el-skeleton v-if="loading" :rows="8" animated />

          <el-result
            v-else-if="errorMessage"
            icon="warning"
            title="获取分析失败"
            :sub-title="errorMessage"
          >
            <template #extra>
              <el-button @click="loadReport">重试</el-button>
            </template>
          </el-result>

          <template v-else-if="report">
            <!-- 结论来源标注：必须出现在最显眼的位置 -->
            <div class="meta">
              <el-tag :type="isRuleBased ? 'warning' : 'success'" effect="dark">
                {{ modelLabel }}
              </el-tag>
              <el-tag v-if="report.fromArchive" type="info" effect="plain">
                <el-icon><Clock /></el-icon>
                历史存档
              </el-tag>
              <span class="muted" style="font-size: 12px">
                生成于 {{ formatIsoDateTime(report.generatedAt) }}
              </span>
            </div>

            <el-alert
              v-if="report.notice"
              :type="isRuleBased ? 'warning' : 'info'"
              :title="report.notice"
              show-icon
              :closable="false"
              style="margin: 12px 0"
            />

            <div class="thesis">
              <div class="thesis__label">核心观点</div>
              <p class="thesis__text">{{ report.thesis || '--' }}</p>
            </div>

            <div class="grid grid--2" style="margin-top: 16px">
              <div class="panel panel--bull">
                <div class="panel__title">看多理由</div>
                <ul>
                  <li v-for="(t, i) in report.bullCase" :key="i">{{ t }}</li>
                  <li v-if="!report.bullCase?.length" class="muted">无</li>
                </ul>
              </div>
              <div class="panel panel--bear">
                <div class="panel__title">看空理由</div>
                <ul>
                  <li v-for="(t, i) in report.bearCase" :key="i">{{ t }}</li>
                  <li v-if="!report.bearCase?.length" class="muted">无</li>
                </ul>
              </div>
            </div>

            <div class="panel" style="margin-top: 16px">
              <div class="panel__title">风险提示</div>
              <ul>
                <li v-for="(t, i) in report.risks" :key="i">{{ t }}</li>
                <li v-if="!report.risks?.length" class="muted">无</li>
              </ul>
            </div>

            <el-descriptions :column="1" border size="small" style="margin-top: 16px">
              <el-descriptions-item label="估值说明">
                {{ report.valuationNote || '--' }}
              </el-descriptions-item>
              <el-descriptions-item label="操作提示">
                {{ report.actionHint || '--' }}
              </el-descriptions-item>
              <el-descriptions-item label="本次分析依据的数据">
                <div v-if="report.dataBasis?.length" class="basis">
                  <el-tag v-for="(b, i) in report.dataBasis" :key="i" size="small" effect="plain">
                    {{ b }}
                  </el-tag>
                </div>
                <span v-else class="muted">未提供数据清单</span>
              </el-descriptions-item>
            </el-descriptions>

            <!--
              免责声明（M3）改成组件：措辞与个股页 AI 解读卡完全一致。
              以前是散在各处的 el-alert，改一处忘一处，
              而合规文本的特点就是「一个字都不能有差异」。
            -->
            <DisclaimerNote :compact="false" class="report__disclaimer" />
          </template>

          <el-empty v-else description="暂无分析结果" />
        </div>
      </section>

      <!-- 历史存档 -->
      <section class="card">
        <div class="card__header">
          <span class="card__title">历史存档</span>
          <span class="muted" style="font-size: 12px">
            同一标的的分析结论对所有用户相同，因此这里展示的是全量存档
          </span>
        </div>
        <div class="card__body">
          <el-timeline v-if="history.length">
            <el-timeline-item
              v-for="h in history"
              :key="h.generatedAt"
              :timestamp="formatIsoDateTime(h.generatedAt)"
              placement="top"
            >
              <div class="hist">
                <el-tag size="small" :type="h.model === 'rule-based' ? 'warning' : 'success'" effect="plain">
                  {{ h.model }}
                </el-tag>
                <span class="hist__thesis">{{ h.thesis }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无历史记录" :image-size="64" />
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.actions {
  display: flex;
  gap: 8px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.thesis {
  padding: 12px 14px;
  background: var(--color-surface-muted);
  border-left: 3px solid var(--color-up);
  border-radius: var(--radius-sm);
}

.thesis__label {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.thesis__text {
  margin: 4px 0 0;
  font-size: 15px;
  line-height: 1.7;
}

/*
  报告正文的免责声明：与结论之间留一条分隔线，
  视觉上表示「以下是声明，不属于结论本身」。
*/
.report__disclaimer {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--color-border);
}

.panel {
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.panel--bull {
  background: var(--tint-up);
}

.panel--bear {
  background: var(--tint-down);
}

.panel__title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
}

.panel ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.8;
}

.basis {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.hist {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.hist__thesis {
  flex: 1;
  line-height: 1.6;
  font-size: 13px;
}
</style>

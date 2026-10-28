<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { MagicStick, Refresh, Clock } from '@element-plus/icons-vue'
import DisclaimerNote from '@/components/DisclaimerNote.vue'
import { useAnalysisTask } from '@/composables/useAnalysisTask'
import { formatIsoDateTime } from '@/api/types'
import type { AnalysisReport } from '@/api/types'
import { track } from '@/utils/track'

/**
 * AI 解读卡（任务书 M3）。
 *
 * <h2>为什么放在个股详情页，而不是只放在独立的分析页</h2>
 * 用户在详情页已经看完了量价与基本面，此时最自然的下一步是
 * 「换个角度看看」。把他赶到一个新页面去点一次「生成」，
 * 中间的 40 秒等待会让大多数人直接放弃。
 * 卡片形态把「看一眼结论」和「看完整报告」拆成两级：
 * 卡片里先给核心观点，想深挖再跳过去。
 *
 * <h2>为什么要有「来自存档」这个标记</h2>
 * 同一标的的结论对所有用户一致，所以后端会把报告存库复用。
 * 复用的好处是省一次模型调用、也省等待，
 * 代价是<b>结论可能不是最新的</b>。这个代价必须由界面如实呈现，
 * 否则用户会以为这是「刚刚算出来的」。
 *
 * <h2>为什么进页面不自动跑</h2>
 * 一次生成要花一次模型调用。用户可能只是路过看一下行情，
 * 自动触发会在他根本不关心的时候把额度烧掉。
 * 因此默认停在 idle，由用户显式点击开始。
 */
const props = defineProps<{
  code: string
  name?: string
}>()

const emit = defineEmits<{
  /** 生成成功后抛出，便于父组件同步其它状态（如刷新历史列表） */
  (e: 'done', report: AnalysisReport): void
}>()

const router = useRouter()
const { phase, report, error, waited, running, run, cancel } = useAnalysisTask()

/** 是否已拿到可展示的结论。 */
const hasReport = computed(() => report.value !== null)

/** 结论来源标签：规则引擎降级 / 大模型 / 存档。 */
const sourceLabel = computed(() => {
  const r = report.value
  if (!r) return ''
  if (r.model === 'rule-based') return '规则引擎推导'
  return `大模型 ${r.model}`
})

const sourceTone = computed<'danger' | 'success'>(() =>
  report.value?.model === 'rule-based' ? 'danger' : 'success',
)

/** 等待秒数，用于「已等待 12 秒」这样的诚实提示。 */
const waitedSeconds = computed(() => Math.round(waited.value / 1000))

async function generate(force = false): Promise<void> {
  track('analysis.card.generate', { code: props.code, force })
  await run(props.code, force)
  const r = report.value
  if (r) emit('done', r)
}

function openFullReport(): void {
  track('analysis.card.open', { code: props.code })
  void router.push({ name: 'analysis-detail', params: { code: props.code } })
}
</script>

<template>
  <section class="card insight">
    <div class="card__header">
      <span class="card__title">AI 解读</span>

      <div class="insight__actions">
        <el-button
          v-if="hasReport"
          text
          size="small"
          :icon="Refresh"
          :loading="running"
          @click="generate(true)"
        >
          重新生成
        </el-button>
        <el-button
          v-if="hasReport"
          text
          size="small"
          type="primary"
          @click="openFullReport"
        >
          查看完整报告
        </el-button>
      </div>
    </div>

    <div class="card__body">
      <!-- 初始态：不自动跑，等用户点 -->
      <div v-if="phase === 'idle'" class="insight__idle">
        <p class="muted insight__idle-text">
          让 AI 基于当前行情、技术指标与财务数据给出一份结构化研判。
          生成通常需要 10~40 秒。
        </p>
        <el-button type="primary" :icon="MagicStick" @click="generate(false)">
          生成 AI 解读
        </el-button>
      </div>

      <!-- 进行中：显示真实等待时间，而不是转圈了事 -->
      <div v-else-if="running" class="insight__loading">
        <el-skeleton :rows="3" animated />
        <p class="muted insight__waiting">
          <el-icon class="is-loading"><Clock /></el-icon>
          已等待 {{ waitedSeconds }} 秒 · 正在调用模型
        </p>
        <el-button size="small" @click="cancel">取消</el-button>
      </div>

      <!-- 失败态 -->
      <div v-else-if="phase === 'FAILED'" class="insight__failed">
        <el-alert type="error" :title="error ?? '生成失败'" show-icon :closable="false" />
        <el-button size="small" :icon="Refresh" style="margin-top: 8px" @click="generate(true)">
          重试
        </el-button>
      </div>

      <!-- 超时：后端可能仍在跑，如实说明 -->
      <div v-else-if="phase === 'timeout'" class="insight__failed">
        <el-alert
          type="warning"
          title="仍在生成中"
          description="已等待超过 90 秒仍未返回。任务可能仍在后端运行，请稍后返回本页查看，或重新生成一次。"
          show-icon
          :closable="false"
        />
        <el-button size="small" :icon="Refresh" style="margin-top: 8px" @click="generate(true)">
          重新生成
        </el-button>
      </div>

      <!-- 结论 -->
      <template v-else-if="report">
        <div class="insight__meta">
          <el-tag :type="sourceTone" effect="dark" size="small">{{ sourceLabel }}</el-tag>
          <el-tag v-if="report.fromArchive" type="info" effect="plain" size="small">
            <el-icon><Clock /></el-icon>
            历史存档
          </el-tag>
          <span class="muted insight__time">
            生成于 {{ formatIsoDateTime(report.generatedAt) }}
          </span>
        </div>

        <el-alert
          v-if="report.notice"
          type="warning"
          :title="report.notice"
          show-icon
          :closable="false"
          style="margin: 10px 0"
        />

        <p class="insight__thesis">{{ report.thesis || '--' }}</p>

        <!-- 只取多空各两条做摘要，完整列表在报告页 -->
        <div class="insight__cols">
          <div>
            <div class="insight__col-title text-up">看多</div>
            <ul>
              <li v-for="(t, i) in (report.bullCase ?? []).slice(0, 2)" :key="i">{{ t }}</li>
              <li v-if="!report.bullCase?.length" class="muted">无</li>
            </ul>
          </div>
          <div>
            <div class="insight__col-title text-down">看空</div>
          <ul>
              <li v-for="(t, i) in (report.bearCase ?? []).slice(0, 2)" :key="i">{{ t }}</li>
              <li v-if="!report.bearCase?.length" class="muted">无</li>
            </ul>
          </div>
        </div>
      </template>

      <!--
        免责声明放在卡片最底部，紧贴结论。
        单独放页脚不够：用户是冲着这份结论做判断的，
        提示必须在他读到结论的同一个视觉单元里。
      -->
      <DisclaimerNote class="insight__disclaimer" />
    </div>
  </section>
</template>

<style scoped>
.insight__actions {
  display: flex;
  gap: 4px;
  align-items: center;
}

.insight__idle {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.insight__idle-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}

.insight__loading {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.insight__waiting {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 12px;
}

.insight__failed {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.insight__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.insight__time {
  font-size: 12px;
}

.insight__thesis {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.8;
}

.insight__cols {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
  font-size: 13px;
}

.insight__cols ul {
  margin: 6px 0 0;
  padding-left: 18px;
  line-height: 1.8;
}

.insight__col-title {
  font-weight: 600;
}

.insight__disclaimer {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}
</style>

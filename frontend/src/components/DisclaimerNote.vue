<script setup lang="ts">
/**
 * 免责声明（任务书 M3）。
 *
 * <h2>为什么要把一句话免责做成组件</h2>
 * 同一句「不构成投资建议」散落在个股页、AI 研判页、自选页，
 * 靠人肉复制迟早会走样——有的地方漏了「AI 生成」，有的地方措辞不同。
 * 合规文本的特点恰恰是<b>一个字都不能有差异</b>，
 * 所以把措辞收敛到一处，各页面只决定「放在哪、什么语气」。
 *
 * <h2>为什么默认是 warning 而不是 info</h2>
 * 这里展示的内容来自大模型，会出错。用 info 的蓝色会与「正常的补充说明」
 * 混在一起；warning 的橙色至少提示「这条需要你自己判断」。
 *
 * <h2>为什么不给「关闭」按钮</h2>
 * 免责声明的可关闭是个经典反模式：用户关掉一次之后再也不出现，
 * 于是最需要提示的场景反而看不到。因此这里没有任何关闭入口。
 */
withDefaults(
  defineProps<{
    /**
     * 展示语气。
     * - `warn`：橙色，用于 AI 生成内容（默认）；
     * - `info`：蓝色，用于数据说明等中性提示。
     */
    tone?: 'warn' | 'info'
    /**
     * 紧凑模式：只留一行，用于卡片底部。
     * 完整模式会额外说明「AI 可能出错」，用在报告正文附近。
     */
    compact?: boolean
  }>(),
  {
    tone: 'warn',
    compact: true,
  },
)
</script>

<template>
  <!--
    用 el-alert 的 type 表达语气，但关掉 show-icon 的默认外边距带来的高度抖动：
    这里固定 :closable="false"，保证不出现「用户关掉声明」的情况。
  -->
  <div class="disclaimer" :class="`disclaimer--${tone}`">
    <span class="disclaimer__tag">
      {{ tone === 'warn' ? 'AI 生成' : '数据说明' }}
    </span>
    <span class="disclaimer__text">
      <template v-if="compact">
        仅供参考，<strong>不构成投资建议</strong>
      </template>
      <template v-else>
        本结论由程序基于公开数据生成，AI 研判可能出错，
        <strong>不构成任何投资建议，据此操作风险自担</strong>
      </template>
    </span>
  </div>
</template>

<style scoped>
.disclaimer {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;
}

/*
  这里的颜色刻意不走 --color-up / --color-down：
  免责声明不是行情信息，用涨跌色会让人误以为「红色=风险信号」是行情含义。
  它属于 UI 状态色，因此单独留一组 token。
*/
.disclaimer--warn {
  color: var(--color-warning);
}

.disclaimer--warn .disclaimer__tag {
  background: color-mix(in srgb, var(--color-warning) 14%, transparent);
}

.disclaimer--info {
  color: var(--color-text-secondary);
}

.disclaimer--info .disclaimer__tag {
  background: color-mix(in srgb, var(--color-text-secondary) 12%, transparent);
}

.disclaimer__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  font-weight: 600;
}
</style>

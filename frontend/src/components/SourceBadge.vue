<script setup lang="ts">
import { computed } from 'vue'
import { useMarketSourceStore } from '@/stores/marketSource'

/**
 * 数据源状态徽标。
 *
 * 这个组件存在的唯一目的是把「当前行情是不是真实数据」这件事讲清楚。
 * 渲染规则集中在 marketSource store 里，这里只负责呈现：
 *   degraded  → 红色「演示数据」+ 悬浮提示原因
 *   !ready    → 黄色「数据源未就绪」
 *   正常      → 绿色，显示主数据源名称
 *
 * 后端确实出现过「displayName 写着『东方财富（真实行情）』但价格是模拟值」
 * 的情况，因此这里绝不允许直接用 displayName 当数据来源。
 */
const source = useMarketSourceStore()

const tagType = computed(() => source.badgeType)
const tip = computed(() => {
  if (!source.status) return '正在获取数据源状态'
  if (source.degraded) {
    return (
      source.status.notice ??
      '上游数据源不可用，当前部分行情为内置演示数据，请勿据此做出投资决策。'
    )
  }
  if (!source.ready) {
    return source.status.notice ?? '数据源尚未就绪，搜索结果可能不完整。'
  }
  return `数据来源于 ${source.status.displayName}`
})
</script>

<template>
  <el-tooltip :content="tip" placement="bottom" :show-after="150">
    <el-tag :type="tagType" size="small" effect="light" class="source-badge">
      <span class="source-badge__dot" />
      {{ source.badgeText }}
    </el-tag>
  </el-tooltip>
</template>

<style scoped>
.source-badge {
  cursor: help;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.source-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
</style>

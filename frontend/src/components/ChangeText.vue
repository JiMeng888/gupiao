<script setup lang="ts">
import { computed } from 'vue'

/**
 * 涨跌数值展示。
 *
 * 把「涨红跌绿」这条约定收在一个组件里，是整个前端最值得做的一处收敛：
 * A 股习惯与欧美相反，只要有一处写反，用户读到的信号就是颠倒的，
 * 而且这种错误在界面上「看起来很合理」，代码评审极易漏掉。
 *
 * 同时统一处理三件事：
 *   - 正数补 `+` 号（不带符号的正数在列表里和涨跌无关的数值难以区分）
 *   - 单位后缀（% / 元 / 无）
 *   - 空值显示为 `--` 而不是 `0`——「没数据」和「持平」是两件事
 */
const props = withDefaults(
  defineProps<{
    /** 数值；null / undefined 会渲染为占位符 */
    value: number | null | undefined
    /** 单位后缀，如 '%' */
    suffix?: string
    /** 是否显示正数前的 + 号 */
    showPlus?: boolean
    /** 小数位 */
    digits?: number
    /** 空值占位文本 */
    placeholder?: string
    /** 数值为 0 时是否按「持平」着色（否则与正数同色） */
    zeroAsFlat?: boolean
  }>(),
  {
    suffix: '',
    showPlus: true,
    digits: 2,
    placeholder: '--',
    zeroAsFlat: true,
  },
)

const isBlank = computed(
  () => props.value === null || props.value === undefined || !Number.isFinite(props.value),
)

const cls = computed(() => {
  if (isBlank.value) return 'text-flat'
  const v = props.value as number
  if (v > 0) return 'text-up'
  if (v < 0) return 'text-down'
  return props.zeroAsFlat ? 'text-flat' : 'text-up'
})

const text = computed(() => {
  if (isBlank.value) return props.placeholder
  const v = props.value as number
  const sign = props.showPlus && v > 0 ? '+' : ''
  return `${sign}${v.toFixed(props.digits)}${props.suffix}`
})
</script>

<template>
  <span class="num" :class="cls">{{ text }}</span>
</template>

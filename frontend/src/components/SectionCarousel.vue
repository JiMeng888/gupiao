<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useSlots } from 'vue'

/**
 * 区块轮播容器。
 *
 * 首页四个区块（大盘指数 / 精选关注 / 最新资讯 / 今日涨幅榜）都要改成轮播，
 * 但轮播本身有十来个参数（autoplay / interval / arrow / indicator-position /
 * trigger / loop / pause-on-hover …）。直接复制四份的结果是：改一次交互策略
 * 要改四个地方，而且很容易漏掉一处，出现「三块悬停暂停、一块不暂停」这种
 * 只有用户操作时才会察觉的差异。所以这里把「策略」收在一处，
 * 各区块只负责描述「第 N 张要画什么」。
 *
 * 三个刻意的取舍：
 *
 * 1. **指示点放外面（indicator-position="outside"）**。
 *    默认的指示点在容器内部底部，会压在卡片内容上——涨幅榜这种自带
 *    表头/背景的行，指示点叠上去既看不清也点不准。放外面则指示点单独占一行，
 *    不与任何内容抢位置。
 *
 * 2. **只有一张时不渲染轮播**。el-carousel 在 autoplay + loop 下即使
 *    只有一项也会持续空转，底部还会残留一个孤立的指示点，看起来像坏了。
 *    单张时走 `single` 插槽平铺，更干净。
 *
 * 3. **悬停暂停用 el-carousel 自带的 pause-on-hover**，不自己监听 mouseenter。
 *    自己实现要处理子元素 mouseenter/mouseleave 的冒泡抖动，库已经处理好了。
 */
const props = withDefaults(
  defineProps<{
    /** 是否正在加载 */
    loading?: boolean
    /** 轮播项数量（0 表示无数据，1 走平铺分支） */
    count?: number
    /** 是否自动播放 */
    autoplay?: boolean
    /** 自动切换间隔（毫秒） */
    interval?: number
    /** 指示点触发方式 */
    indicatorTrigger?: 'hover' | 'click'
    /** 每张高度，如 '92px'。不传则由内容撑开 */
    height?: string
    /** 左右箭头显示时机 */
    arrow?: 'always' | 'hover' | 'never'
  }>(),
  {
    loading: false,
    count: 0,
    autoplay: true,
    interval: 4000,
    indicatorTrigger: 'click',
    height: '',
    arrow: 'hover',
  },
)

const slots = useSlots()

/**
 * 用 `count === 1` 而不是 `count <= 1`：count 为 0 是「没有数据」，
 * 必须走空态，不能和「只有一张」混为一谈——两者提示文案完全不同。
 */
const showSingle = computed(() => props.count === 1)

const hasLoadingSlot = computed(() => Boolean(slots.loading))
const hasEmptySlot = computed(() => Boolean(slots.empty))
const hasSingleSlot = computed(() => Boolean(slots.single))

/**
 * el-carousel 内部监听 window resize 重算卡片宽度，但
 * 「父容器变宽而窗口没变」的场景（侧边栏折叠、卡片布局切换）
 * 不会触发，所以这里补一个 ResizeObserver。
 *
 * scale 的值本身没有意义，只用来驱动一次重新渲染，
 * 让 el-carousel 重新测量。因此取 clientWidth 保证尺寸真变时值才变。
 */
const rootEl = ref<HTMLElement | null>(null)
const viewportWidth = ref(0)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!rootEl.value || typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(() => {
    viewportWidth.value = rootEl.value?.clientWidth ?? 0
  })
  observer.observe(rootEl.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div ref="rootEl" class="sec-carousel" :data-vw="viewportWidth">
    <!-- 加载中：骨架行数由调用方决定，容器只负责占位 -->
    <template v-if="loading">
      <slot v-if="hasLoadingSlot" name="loading" />
      <el-skeleton v-else :rows="4" animated />
    </template>

    <!-- 有数据且不止一张：正常轮播 -->
    <el-carousel
      v-else-if="count > 1"
      :autoplay="autoplay"
      :interval="interval"
      :trigger="indicatorTrigger"
      :arrow="arrow"
      :height="height || undefined"
      indicator-position="outside"
      :pause-on-hover="true"
      :motion-blur="false"
    >
      <el-carousel-item v-for="i in count" :key="i">
        <slot :index="i - 1" :no="i" />
      </el-carousel-item>
    </el-carousel>

    <!-- 恰好一张：平铺，不空转也不会留一个孤立的指示点 -->
    <slot v-else-if="showSingle && hasSingleSlot" name="single" />
    <slot v-else-if="showSingle" :index="0" :no="1" />

    <!-- 没有数据 -->
    <slot v-else-if="hasEmptySlot" name="empty" />
    <el-empty v-else description="暂无数据" :image-size="72" />
  </div>
</template>

<style scoped>
.sec-carousel {
  width: 100%;
}

/**
 * 指示点默认是 4px 小圆点，在白色卡片上对比度偏低。
 * 这里加深未激活态、把激活态拉成小横条，避免「点到第几张」要凑近看。
 * 颜色走设计变量：与站内 `--color-up` 一致，不额外引入一套色板。
 */
.sec-carousel :deep(.el-carousel__indicators--outside) {
  margin-top: 8px;
}

.sec-carousel :deep(.el-carousel__indicator button) {
  width: 8px;
  height: 8px;
  background: #c9cdd4;
  opacity: 0.7;
  transition: width 0.2s ease, background 0.2s ease, opacity 0.2s ease;
}

.sec-carousel :deep(.el-carousel__indicator.is-active button) {
  width: 20px;
  border-radius: 4px;
  background: var(--color-up);
  opacity: 1;
}

.sec-carousel :deep(.el-carousel__indicator:hover button) {
  opacity: 1;
}

/**
 * el-carousel__item 默认 absolute + overflow:hidden，内容超出即被裁掉。
 * 各区块内容高度不一致（长标题、榜单表格），因此放开裁切，
 * 让内容自己决定溢出行为，避免出现「同一条目在 2fps 下被切掉半行」。
 */
.sec-carousel :deep(.el-carousel__item) {
  overflow: visible;
}
</style>

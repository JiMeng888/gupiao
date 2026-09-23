<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { newsApi } from '@/api/endpoints'
import type { NewsChannelView, NewsItem } from '@/api/types'
import { formatEpochSeconds } from '@/api/types'

/**
 * 资讯。
 *
 * 一个必须向使用者说明的事实：<b>资讯接口失败时后端返回空列表而不是报错</b>
 * （资讯属于「锦上添花」型内容，不该因此让页面报错）。
 * 所以「列表为空」既可能是真的没有内容，也可能是上游不可用。
 * 界面上用不同的文案区分这两种情况，避免用户以为「今天没有新闻」。
 */
const channels = ref<NewsChannelView[]>([])
const activeChannel = ref('all')
const items = ref<NewsItem[]>([])
const loading = ref(false)
const loadedOnce = ref(false)
const failed = ref(false)

onMounted(async () => {
  await loadChannels()
  await load()
})

async function loadChannels(): Promise<void> {
  try {
    channels.value = await newsApi.channels()
  } catch {
    // 频道列表拿不到时退化成「全部」，不阻塞资讯展示
    channels.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  failed.value = false
  try {
    items.value = await newsApi.list(activeChannel.value, 30)
  } catch {
    items.value = []
    failed.value = true
  } finally {
    loading.value = false
    loadedOnce.value = true
  }
}

async function onChannelChange(): Promise<void> {
  await load()
}
</script>

<template>
  <div class="page">
    <section class="card">
      <div class="card__header">
        <span class="card__title">财经资讯</span>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
      <div class="card__body">
        <el-tabs v-model="activeChannel" @tab-change="onChannelChange">
          <el-tab-pane
            v-for="c in channels"
            :key="c.id"
            :label="c.name"
            :name="c.id"
          />
        </el-tabs>

        <el-skeleton v-if="loading && !loadedOnce" :rows="8" animated />

        <el-alert
          v-else-if="failed"
          type="warning"
          title="资讯源暂时不可用"
          description="上游资讯接口请求失败。后端对资讯采用「失败即降级为空列表」的策略，不影响其他功能。请稍后重试。"
          show-icon
          :closable="false"
        />

        <el-empty
          v-else-if="!items.length"
          description="该频道暂无内容（也可能是上游返回为空，稍后可重试）"
          :image-size="80"
        />

        <ul v-else class="list">
          <!-- URL 与标题都来自第三方上游，属于不可信内容：
               这里用文本插值，绝不使用 v-html -->
          <li v-for="item in items" :key="item.id" class="item">
            <div class="item__head">
              <a
                class="item__title"
                :href="item.url"
                target="_blank"
                rel="noopener noreferrer"
              >
                {{ item.title }}
              </a>
              <el-tag v-if="item.channel" size="small" type="info" effect="plain">
                {{ item.channel }}
              </el-tag>
            </div>
            <p v-if="item.intro" class="item__intro muted">{{ item.intro }}</p>
            <div class="item__meta muted">
              <span v-if="item.source">{{ item.source }}</span>
              <span>{{ item.timeText || formatEpochSeconds(item.ctime) }}</span>
              <span v-for="tag in item.tags" :key="tag" class="tag">{{ tag }}</span>
            </div>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.item {
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}

.item:last-child {
  border-bottom: none;
}

.item__head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.item__title {
  flex: 1;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.5;
}

.item__title:hover {
  color: var(--color-up);
}

.item__intro {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item__meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  margin-top: 6px;
}

.tag {
  color: var(--color-text-secondary);
}
</style>

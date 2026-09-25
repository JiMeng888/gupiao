<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import DefaultLayout from '@/layouts/DefaultLayout.vue'

/**
 * 根组件。
 *
 * 做两件事：
 *   1. 决定当前路由是否套用带导航的布局。登录页要独占整屏（meta.hideChrome），
 *      其余页面走 DefaultLayout。
 *   2. 提供 Element Plus 的中文语言包。按需引入后没有 app.use(ElementPlus) 这一步，
 *      语言包只能由 ElConfigProvider 提供；漏掉它组件内置文案会退回英文
 *      （如空表格的 "No Data"）。
 */
const route = useRoute()
const bare = computed(() => Boolean(route.meta.hideChrome))
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="bare" />
    <DefaultLayout v-else />
  </el-config-provider>
</template>

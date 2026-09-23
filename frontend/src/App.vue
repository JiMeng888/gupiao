<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'

/**
 * 根组件。
 *
 * 只做一件事：决定当前路由是否套用带导航的布局。
 * 登录页要独占整屏（meta.hideChrome），其余页面走 DefaultLayout。
 */
const route = useRoute()
const bare = computed(() => Boolean(route.meta.hideChrome))
</script>

<template>
  <router-view v-if="bare" />
  <DefaultLayout v-else />
</template>

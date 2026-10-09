<template>
  <div id="app">
    <!-- 等待用户信息加载完成后再渲染布局，防止页面刷新时闪现登录页 -->
    <BasicLayout v-if="loginUserStore.initialized" />
    <div v-else class="app-loading">
      <a-spin size="large" tip="加载中..." />
    </div>
  </div>
</template>

<script setup lang="ts">
import BasicLayout from './layout/BasicLayout.vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const loginUserStore = useLoginUserStore()
loginUserStore.fetchLoginUser()
</script>

<style>
.app-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
}
</style>

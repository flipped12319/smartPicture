<template>
  <div id="basicLayout">
    <a-layout style="min-height: 100vh">
      <a-layout-header class="header"><GlobalHeader></GlobalHeader></a-layout-header>

      <a-layout>
        <GlobalSider class="sider" />
        <a-layout-content class="content" :class="{ 'content--chat': $route.path === '/ai/chat' }">
          <RouterView></RouterView>
        </a-layout-content>
      </a-layout>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalSider from '@/components/GlobalSider.vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
const loginUserStore = useLoginUserStore()
</script>

<style scoped>
#basicLayout {
}
#basicLayout .header {
  padding-inline: 20px;
  margin-bottom: 16px;
  color: unset;
  background: white;
}

#basicLayout .content {
  background: linear-gradient(to right, #fefefe, #fff);
  margin-bottom: 28px;
  padding: 20px;
}

#basicLayout .footer {
  background: #efefef;
  padding: 16px;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
}

/* 聊天页面：去掉内边距和底部间距，撑满高度 */
#basicLayout .content--chat {
  padding: 0;
  margin-bottom: 0;
  display: flex;
  flex-direction: column;
}

/* 聊天页面内 RouterView 撑满 */
#basicLayout .content--chat > * {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

#basicLayout .sider {
  background: #fff;
  padding-top: 20px;
  border-right: 0.5px solid #eee;
}

#basicLayout :deep(.ant-menu-root) {
  border-bottom: none !important;
  border-inline-end: none !important;
}
</style>

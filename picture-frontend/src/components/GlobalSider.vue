<template>
  <div id="globalSider">
    <a-layout-sider class="sider" width="200" v-if="loginUserStore.loginUser.id">
      <a-menu
        mode="inline"
        v-model:selectedKeys="current"
        :items="menuItems"
        @click="doMenuClick"
      />
    </a-layout-sider>
  </div>
</template>
<script lang="ts" setup>
import { useRouter } from 'vue-router'
import { ref } from 'vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
const loginUserStore = useLoginUserStore()

// 菜单列表
const menuItems = [
  {
    key: '/',
    label: '公共图库',
  },
  {
    key: '/my_space',
    label: '我的空间',
  },
]

const router = useRouter()

// 当前选中菜单
const current = ref<string[]>([])
// 监听路由变化，更新当前选中菜单
router.afterEach((to, from, failure) => {
  current.value = [to.path]
})

// 路由跳转事件
const doMenuClick = ({ key }: { key: string }) => {
  router.push({
    path: key,
  })
}
</script>
<style scoped></style>

<template>
  <div id="mySpacePage">
    <a-flex justify="space-between" align="center" style="margin-bottom: 16px">
      <h2>我的空间</h2>
      <a-button type="primary" @click="router.push('/add_space')">+ 创建空间</a-button>
    </a-flex>

    <a-spin :spinning="loading">
      <a-empty
        v-if="spaceList.length === 0"
        description="还没有空间，先去创建一个吧（也可以等待其他空间管理员邀请你）"
      />
      <a-row v-else :gutter="[16, 16]">
        <a-col v-for="space in spaceList" :key="space.id" :xs="24" :sm="12" :md="8">
          <a-card hoverable @click="router.push(`/space/${space.id}`)">
            <a-card-meta :title="space.spaceName">
              <template #description>
                <a-space style="margin-bottom: 8px">
                  <a-tag :color="space.spaceType === SPACE_TYPE_ENUM.TEAM ? 'purple' : 'green'">
                    {{ SPACE_TYPE_MAP[space.spaceType ?? 0] }}
                  </a-tag>
                  <a-tag>{{ SPACE_LEVEL_MAP[space.spaceLevel ?? 0] }}</a-tag>
                  <a-tag v-if="String(space.userId) !== String(loginUserStore.loginUser?.id)">
                    我加入的
                  </a-tag>
                </a-space>
                <a-progress
                  :percent="calcPercent(space.totalSize, space.maxSize)"
                  size="small"
                  :format="() => `${space.totalCount ?? 0} / ${space.maxCount ?? 0} 张`"
                />
              </template>
            </a-card-meta>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>

    <a-pagination
      style="text-align: right; margin-top: 16px"
      v-model:current="searchParams.current"
      v-model:pageSize="searchParams.pageSize"
      :total="total"
      :show-total="() => `空间总数 ${total}`"
      @change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listMySpaceByPage } from '@/api-custom/spaceUser'
import type { SpaceVOExt } from '@/api-custom/spaceUser'
import { SPACE_LEVEL_MAP, SPACE_TYPE_ENUM, SPACE_TYPE_MAP } from '@/constants/space'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const spaceList = ref<SpaceVOExt[]>([])
const total = ref(0)
const loading = ref(true)

const searchParams = reactive({
  current: 1,
  pageSize: 12,
})

const calcPercent = (totalSize?: number, maxSize?: number) => {
  if (!maxSize) {
    return 0
  }
  return Number((((totalSize ?? 0) * 100) / maxSize).toFixed(1))
}

// 获取「我创建的 + 我加入的」空间
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listMySpaceByPage({ ...searchParams })
    if (res.data.code === 0) {
      spaceList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取空间列表失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取空间列表失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number, size: number) => {
  searchParams.current = page
  searchParams.pageSize = size
  fetchData()
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped></style>

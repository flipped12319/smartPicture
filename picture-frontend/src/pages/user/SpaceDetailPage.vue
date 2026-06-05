<template>
  <!-- 空间信息 -->
  <a-flex justify="space-between">
    <h2>{{ space.spaceName }}（私有空间）</h2>
    <a-space size="middle">
      <a-button type="primary" @click="router.push(`/add_picture?spaceId=${id}`)">
        + 创建图片
      </a-button>
      <a-tooltip :title="`占用空间 ${space.totalSize} / ${space.maxSize}`">
        <a-progress
          type="circle"
          :percent="((space.totalSize * 100) / space.maxSize).toFixed(1)"
          :size="42"
        />
      </a-tooltip>
    </a-space>
  </a-flex>
  <!-- 图片列表 -->
  <PictureList :dataList="dataList" :loading="loading" />
  <a-pagination
    style="text-align: right"
    v-model:current="searchParams.current"
    v-model:pageSize="searchParams.pageSize"
    :total="total"
    :show-total="() => `图片总数 ${total} / ${space.maxCount}`"
    @change="onPageChange"
  />
</template>
<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController'
import { listPictureVoByPageUsingPost } from '@/api/fileController'
import { message } from 'ant-design-vue'
import PictureList from '@/components/PictureList.vue'

const router = useRouter()
const props = defineProps<{
  id: string | number
}>()
const space = ref<API.SpaceVO>({})
console.log('space', space)
console.log('space', space.value)
console.log(space.value.id) // 输出实际 id
console.log(space.value.spaceName) // 输出 space1

// 获取空间详情
const fetchSpaceDetail = async () => {
  try {
    const res = await getSpaceVoByIdUsingGet({
      id: props.id,
    })
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
      console.log(space.value.id) // 输出实际 id
      console.log(space.value.spaceName) // 输出 space1
    } else {
      message.error('获取空间详情失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取空间详情失败：' + e.message)
  }
}

// 数据
const dataList = ref([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 20,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 分页参数
const onPageChange = (page, pageSize) => {
  searchParams.current = page
  searchParams.pageSize = pageSize
  fetchData()
}

// 获取数据
const fetchData = async () => {
  loading.value = true
  // 转换搜索参数
  const params = {
    spaceId: props.id,
    nullSpaceId: false,
    ...searchParams,
  }
  console.log('params ', params)

  const res = await listPictureVoByPageUsingPost(params)
  if (res.data.data) {
    dataList.value = res.data.data.records ?? []
    console.log('datalist', dataList.value)

    total.value = res.data.data.total ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

// 页面加载时请求一次
onMounted(() => {
  fetchSpaceDetail()
  fetchData()
})
</script>

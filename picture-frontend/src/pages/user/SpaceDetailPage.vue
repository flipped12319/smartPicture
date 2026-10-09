<template>
  <!-- 空间信息 -->
  <a-flex justify="space-between" align="center">
    <a-space align="center">
      <h2 style="margin: 0">{{ space.spaceName }}</h2>
      <a-tag :color="isTeamSpace ? 'purple' : 'green'">
        {{ SPACE_TYPE_MAP[space.spaceType ?? 0] }}
      </a-tag>
      <a-tag>我的权限：{{ SPACE_USER_ROLE_MAP[currentRole] }}</a-tag>
    </a-space>
    <a-space size="middle">
      <a-button v-if="isTeamSpace" @click="router.push(`/space/member/${id}`)">
        成员管理
      </a-button>
      <a-button v-if="canUpload" type="primary" @click="router.push(`/add_picture?spaceId=${id}`)">
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

  <!-- 批量操作栏（需要「能看能上传还能编辑」权限） -->
  <a-flex v-if="canEdit" justify="space-between" align="center" style="margin: 16px 0">
    <a-space>
      <a-checkbox
        :checked="isAllSelected"
        :indeterminate="isPartiallySelected"
        :disabled="dataList.length === 0"
        @change="toggleSelectAll"
      >
        全选本页
      </a-checkbox>
      <a-typography-text type="secondary">已选 {{ selectedIds.length }} 张</a-typography-text>
    </a-space>
    <a-space>
      <a-button :disabled="selectedIds.length === 0" @click="clearSelection">取消选择</a-button>
      <a-button
        type="primary"
        danger
        :disabled="selectedIds.length === 0"
        :loading="deleting"
        @click="doBatchDelete"
      >
        批量删除
      </a-button>
    </a-space>
  </a-flex>

  <!-- 图片列表 -->
  <PictureList
    v-model:selectedIds="selectedIds"
    :dataList="dataList"
    :loading="loading"
    selectable
  />
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
import { onMounted, ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController'
import { listPictureVoByPageUsingPost } from '@/api/fileController'
import { Modal, message } from 'ant-design-vue'
import PictureList from '@/components/PictureList.vue'
import request from '@/request'
import type { SpaceVOExt } from '@/api-custom/spaceUser'
import { SPACE_TYPE_ENUM, SPACE_TYPE_MAP, SPACE_USER_ROLE_ENUM, SPACE_USER_ROLE_MAP } from '@/constants/space'

/** 批量删除接口的响应结构（对应后端 BaseResponse<Integer>） */
interface BatchDeleteResponse {
  code?: number
  data?: number
  message?: string
}

const router = useRouter()
const props = defineProps<{
  id: string | number
}>()
const space = ref<SpaceVOExt>({})

// 当前用户在该空间中的角色（0-只能看 1-能看能上传 2-能看能上传还能编辑 3-管理员）
const currentRole = computed(() => space.value.currentUserRole ?? SPACE_USER_ROLE_ENUM.VIEWER)
const isTeamSpace = computed(() => space.value.spaceType === SPACE_TYPE_ENUM.TEAM)
// 能看能上传及以上
const canUpload = computed(() => currentRole.value >= SPACE_USER_ROLE_ENUM.UPLOADER)
// 能看能上传还能编辑及以上
const canEdit = computed(() => currentRole.value >= SPACE_USER_ROLE_ENUM.EDITOR)
console.log('space', space)
console.log('space', space.value)
console.log(space.value.id) // 输出实际 id
console.log(space.value.spaceName) // 输出 space1

// 获取空间详情
const fetchSpaceDetail = async () => {
  try {
    const res = await getSpaceVoByIdUsingGet({
      // 只做类型断言、不做数值转换，保证雪花 id 精度不丢失
      id: props.id as number,
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
const dataList = ref<API.PictureVO[]>([])
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
const onPageChange = (page: number, pageSize: number) => {
  searchParams.current = page
  searchParams.pageSize = pageSize
  // 换页后清空选中，避免选中项与当前页内容对不上
  clearSelection()
  fetchData()
}

// 获取数据
const fetchData = async () => {
  loading.value = true
  // 转换搜索参数
  const params = {
    // 同上，只做类型断言
    spaceId: props.id as number,
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

// ──────────────────── 批量删除 ────────────────────

// 已选中的图片 id
const selectedIds = ref<number[]>([])
// 删除请求进行中
const deleting = ref(false)

// 当前页是否全部选中
const isAllSelected = computed(
  () => dataList.value.length > 0 && selectedIds.value.length === dataList.value.length,
)

// 当前页是否部分选中
const isPartiallySelected = computed(() => selectedIds.value.length > 0 && !isAllSelected.value)

// 全选 / 取消全选当前页
const toggleSelectAll = () => {
  selectedIds.value = isAllSelected.value
    ? []
    : (dataList.value.map((picture) => picture.id).filter((id) => id != null) as number[])
}

// 清空选择
const clearSelection = () => {
  selectedIds.value = []
}

// 批量删除选中的图片
const doBatchDelete = () => {
  const ids = [...selectedIds.value]
  if (ids.length === 0) {
    return
  }
  Modal.confirm({
    title: '确认批量删除',
    content: `确定要删除选中的 ${ids.length} 张图片吗？删除后不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      deleting.value = true
      try {
        // 注：后端接口为 POST /api/delete/batch。
        // 执行 npm run openapi 重新生成客户端后，可替换为 src/api 中生成的调用方法。
        const res = await request<BatchDeleteResponse>('/api/delete/batch', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          data: { ids },
        })
        if (res.data.code === 0) {
          message.success(`成功删除 ${res.data.data ?? ids.length} 张图片`)
          clearSelection()
          // 刷新图片列表与空间额度
          await fetchData()
          await fetchSpaceDetail()
        } else {
          message.error('批量删除失败：' + (res.data.message ?? '未知错误'))
        }
      } catch (e: any) {
        message.error('批量删除失败：' + e.message)
      } finally {
        deleting.value = false
      }
    },
  })
}

// 页面加载时请求一次
onMounted(() => {
  fetchSpaceDetail()
  fetchData()
})
</script>

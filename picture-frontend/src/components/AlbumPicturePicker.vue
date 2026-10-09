<template>
  <div class="album-picture-picker">
    <!-- 操作栏 -->
    <a-flex justify="space-between" align="center" style="margin-bottom: 8px">
      <a-checkbox
        :checked="isAllSelected"
        :indeterminate="isPartiallySelected"
        :disabled="dataList.length === 0"
        @change="toggleSelectAll"
      >
        全选本页
      </a-checkbox>
      <a-typography-text type="secondary">共 {{ total }} 张</a-typography-text>
    </a-flex>
    <!-- 图片列表，复用多选能力 -->
    <PictureList
      v-model:selectedIds="localSelectedIds"
      :dataList="dataList"
      :loading="loading"
      selectable
    />
    <a-pagination
      style="text-align: right; margin-top: 8px"
      v-model:current="current"
      v-model:pageSize="pageSize"
      :total="total"
      :show-total="() => `共 ${total} 张`"
      @change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listPictureVoByPageUsingPost } from '@/api/fileController'
import PictureList from '@/components/PictureList.vue'

interface Props {
  /** 图片来源：space-我的私人空间；public-公共图库 */
  source: 'space' | 'public'
  /** 当 source 为 space 时必传（后端 Long 以字符串下发，这里保持原样透传） */
  spaceId?: string | number
  /** 已选中的图片 id，可 v-model:selectedIds */
  selectedIds?: number[]
}

const props = withDefaults(defineProps<Props>(), {
  spaceId: undefined,
  selectedIds: () => [],
})

const emit = defineEmits<{
  (e: 'update:selectedIds', ids: number[]): void
}>()

// 已选中的图片 id 由父组件维护：在两个 tab 之间共享同一份选择
const localSelectedIds = computed({
  get: () => props.selectedIds,
  set: (ids: number[]) => emit('update:selectedIds', ids),
})

// 数据
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(false)
const current = ref(1)
const pageSize = ref(12)

// 当前页是否全部选中
const isAllSelected = computed(
  () => dataList.value.length > 0 && selectedCountInPage.value === dataList.value.length,
)
// 当前页是否部分选中
const isPartiallySelected = computed(
  () => selectedCountInPage.value > 0 && !isAllSelected.value,
)
// 当前页中被选中的数量
const selectedCountInPage = computed(
  () => dataList.value.filter((picture) => picture.id != null && props.selectedIds.includes(picture.id)).length,
)

// 全选 / 取消全选当前页（跨 tab 共享选择，因此只增删当前页的图片）
const toggleSelectAll = () => {
  const pageIds = dataList.value
    .map((picture) => picture.id)
    .filter((id) => id != null) as number[]
  if (isAllSelected.value) {
    localSelectedIds.value = props.selectedIds.filter((id) => !pageIds.includes(id))
  } else {
    localSelectedIds.value = Array.from(new Set([...props.selectedIds, ...pageIds]))
  }
}

// 获取图片列表
const fetchData = async () => {
  loading.value = true
  try {
    const params: API.PictureQueryRequest = {
      current: current.value,
      pageSize: pageSize.value,
      sortField: 'createTime',
      sortOrder: 'descend',
    }
    if (props.source === 'space') {
      // 自动生成的类型把 Long 声明成 number，但后端实际以字符串下发；
      // 这里只做类型断言、不做数值转换，保证雪花 id 精度不丢失
      params.spaceId = props.spaceId as number
      params.nullSpaceId = false
    } else {
      // 公共图库：spaceId 为空
      params.nullSpaceId = true
    }
    const res = await listPictureVoByPageUsingPost(params)
    if (res.data.code === 0) {
      dataList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取图片失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取图片失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number, size: number) => {
  current.value = page
  pageSize.value = size
  fetchData()
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped></style>

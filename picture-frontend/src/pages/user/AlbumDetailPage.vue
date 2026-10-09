<template>
  <div id="albumDetailPage">
    <!-- 相册信息 -->
    <a-flex justify="space-between" align="center">
      <h2>{{ album.name }}</h2>
      <a-space>
        <a-button @click="openEditModal">编辑</a-button>
        <a-button danger @click="doDeleteAlbum">删除相册</a-button>
      </a-space>
    </a-flex>
    <a-typography-paragraph type="secondary" style="margin-bottom: 4px">
      {{ album.introduction }}
    </a-typography-paragraph>
    <a-typography-text type="secondary">共 {{ album.pictureCount ?? 0 }} 张图片</a-typography-text>

    <a-divider style="margin: 16px 0" />

    <!-- 批量操作栏 -->
    <a-flex justify="space-between" align="center" style="margin-bottom: 16px">
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
      <a-button
        danger
        :disabled="selectedIds.length === 0"
        :loading="removing"
        @click="doRemovePictures"
      >
        移出相册
      </a-button>
    </a-flex>

    <!-- 图片列表 -->
    <PictureList
      v-model:selectedIds="selectedIds"
      :dataList="dataList"
      :loading="loading"
      selectable
    />
    <a-pagination
      style="text-align: right; margin-top: 16px"
      v-model:current="searchParams.current"
      v-model:pageSize="searchParams.pageSize"
      :total="total"
      :show-total="() => `图片总数 ${total}`"
      @change="onPageChange"
    />

    <!-- 编辑相册 -->
    <a-modal v-model:open="editModalOpen" title="编辑相册" :confirm-loading="editing" @ok="doEditAlbum">
      <a-form layout="vertical" :model="editForm">
        <a-form-item label="相册名称" required>
          <a-input v-model:value="editForm.name" :maxlength="64" show-count />
        </a-form-item>
        <a-form-item label="相册说明" required>
          <a-textarea v-model:value="editForm.introduction" :rows="3" :maxlength="512" show-count />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import {
  deleteAlbumById,
  editAlbum,
  getAlbumVOById,
  listAlbumPictureByPage,
  removePictureFromAlbum,
} from '@/api-custom/album'
import type { AlbumVO } from '@/api-custom/album'
import PictureList from '@/components/PictureList.vue'

const props = defineProps<{
  id: string | number
}>()

const router = useRouter()
// 注意：后端把所有 Long 都序列化成了字符串（见 JsonConfig），雪花 id 有 19 位，
// 一旦用 Number() 转换就会超出 JS 安全整数范围导致精度丢失、查不到数据，
// 所以这里必须原样保留字符串形态。
const albumId = computed(() => props.id)

const album = ref<AlbumVO>({})
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(true)

// 已选中的图片 id
const selectedIds = ref<number[]>([])
const removing = ref(false)

// 编辑弹窗
const editModalOpen = ref(false)
const editing = ref(false)
const editForm = reactive({
  name: '',
  introduction: '',
})

const searchParams = reactive({
  current: 1,
  pageSize: 20,
})

// 当前页是否全部选中
const isAllSelected = computed(
  () => dataList.value.length > 0 && selectedIds.value.length === dataList.value.length,
)
const isPartiallySelected = computed(() => selectedIds.value.length > 0 && !isAllSelected.value)

// 获取相册详情
const fetchAlbumDetail = async () => {
  const res = await getAlbumVOById(albumId.value)
  if (res.data.code === 0 && res.data.data) {
    album.value = res.data.data
  } else {
    message.error('获取相册详情失败：' + res.data.message)
  }
}

// 获取相册内的图片
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listAlbumPictureByPage({
      albumId: albumId.value,
      current: searchParams.current,
      pageSize: searchParams.pageSize,
    })
    if (res.data.code === 0) {
      dataList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取相册图片失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取相册图片失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number, size: number) => {
  searchParams.current = page
  searchParams.pageSize = size
  selectedIds.value = []
  fetchData()
}

// 全选 / 取消全选当前页
const toggleSelectAll = () => {
  selectedIds.value = isAllSelected.value
    ? []
    : ((dataList.value.map((picture) => picture.id).filter((id) => id != null)) as number[])
}

// 批量移出相册（不会删除图片本身）
const doRemovePictures = () => {
  const pictureIds = [...selectedIds.value]
  if (pictureIds.length === 0) {
    return
  }
  Modal.confirm({
    title: '确认移出相册',
    content: `确定要把选中的 ${pictureIds.length} 张图片移出相册吗？图片本身不会被删除。`,
    okText: '移出',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      removing.value = true
      try {
        const res = await removePictureFromAlbum({ albumId: albumId.value, pictureIds })
        if (res.data.code === 0) {
          message.success(`已移出 ${res.data.data ?? pictureIds.length} 张图片`)
          selectedIds.value = []
          await fetchData()
          await fetchAlbumDetail()
        } else {
          message.error('移出失败：' + res.data.message)
        }
      } catch (e: any) {
        message.error('移出失败：' + e.message)
      } finally {
        removing.value = false
      }
    },
  })
}

// 打开编辑弹窗
const openEditModal = () => {
  editForm.name = album.value.name ?? ''
  editForm.introduction = album.value.introduction ?? ''
  editModalOpen.value = true
}

// 保存相册信息
const doEditAlbum = async () => {
  if (!editForm.name.trim()) {
    message.error('请输入相册名称')
    return
  }
  if (!editForm.introduction.trim()) {
    message.error('请输入相册说明')
    return
  }
  editing.value = true
  try {
    const res = await editAlbum({
      id: albumId.value,
      name: editForm.name.trim(),
      introduction: editForm.introduction.trim(),
    })
    if (res.data.code === 0) {
      message.success('保存成功')
      editModalOpen.value = false
      await fetchAlbumDetail()
    } else {
      message.error('保存失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('保存失败：' + e.message)
  } finally {
    editing.value = false
  }
}

// 删除相册
const doDeleteAlbum = () => {
  Modal.confirm({
    title: '确认删除相册',
    content: `确定要删除相册「${album.value.name}」吗？相册内的图片不会被删除。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const res = await deleteAlbumById(albumId.value)
      if (res.data.code === 0) {
        message.success('删除成功')
        router.push('/my_album')
      } else {
        message.error('删除失败：' + res.data.message)
      }
    },
  })
}

onMounted(() => {
  fetchAlbumDetail()
  fetchData()
})
</script>

<style scoped></style>

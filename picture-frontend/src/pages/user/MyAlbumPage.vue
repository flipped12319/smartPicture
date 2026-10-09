<template>
  <div id="myAlbumPage">
    <a-flex justify="space-between" align="center" style="margin-bottom: 16px">
      <h2>我的相册</h2>
      <a-button type="primary" @click="router.push('/add_album')">+ 创建相册</a-button>
    </a-flex>

    <a-spin :spinning="loading">
      <a-empty v-if="albumList.length === 0" description="还没有相册，创建一个来整理图片吧" />
      <a-row v-else :gutter="[16, 16]">
        <a-col v-for="album in albumList" :key="album.id" :xs="24" :sm="12" :md="8" :lg="6">
          <a-card hoverable @click="router.push(`/album/${album.id}`)">
            <template #cover>
              <div class="album-cover">
                <img v-if="album.coverUrl" :src="album.coverUrl" :alt="album.name" />
                <div v-else class="album-cover__empty">暂无图片</div>
              </div>
            </template>
            <a-card-meta :title="album.name">
              <template #description>
                <div class="album-introduction" :title="album.introduction">
                  {{ album.introduction }}
                </div>
                <a-flex justify="space-between" align="center">
                  <a-typography-text type="secondary">
                    {{ album.pictureCount ?? 0 }} 张图片
                  </a-typography-text>
                  <a-button type="link" danger size="small" @click.stop="doDelete(album)">
                    删除
                  </a-button>
                </a-flex>
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
      :show-total="() => `相册总数 ${total}`"
      @change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { deleteAlbumById, listAlbumVOByPage } from '@/api-custom/album'
import type { AlbumVO } from '@/api-custom/album'

const router = useRouter()

const albumList = ref<AlbumVO[]>([])
const total = ref(0)
const loading = ref(true)

const searchParams = reactive({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 获取我的相册列表
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listAlbumVOByPage({ ...searchParams })
    if (res.data.code === 0) {
      albumList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取相册列表失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取相册列表失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number, size: number) => {
  searchParams.current = page
  searchParams.pageSize = size
  fetchData()
}

// 删除相册（只删相册，相册内的图片不受影响）
const doDelete = (album: AlbumVO) => {
  const albumId = album.id
  if (!albumId) {
    return
  }
  Modal.confirm({
    title: '确认删除相册',
    content: `确定要删除相册「${album.name}」吗？相册内的图片不会被删除。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const res = await deleteAlbumById(albumId)
      if (res.data.code === 0) {
        message.success('删除成功')
        fetchData()
      } else {
        message.error('删除失败：' + res.data.message)
      }
    },
  })
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.album-cover {
  height: 160px;
  overflow: hidden;
  background: #f5f5f5;
}

.album-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.album-cover__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #bfbfbf;
}

.album-introduction {
  margin-bottom: 8px;
  color: rgba(0, 0, 0, 0.45);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<template>
  <div id="pictureDetailPage">
    <a-row :gutter="[16, 16]">
      <!-- 图片展示区 -->
      <a-col :sm="24" :md="16" :xl="18">
        <a-card title="图片预览">
          <a-image style="max-height: 600px; object-fit: contain" :src="picture.url" />
        </a-card>
      </a-col>
      <!-- 图片信息区 -->
      <a-col :sm="24" :md="8" :xl="6">
        <a-card title="图片信息">
          <a-descriptions :column="1">
            <a-descriptions-item label="作者">
              <a-space>
                <a-avatar :size="24" :src="picture.user?.userAvatar" />
                <div>{{ picture.user?.userName }}</div>
              </a-space>
            </a-descriptions-item>
            <a-descriptions-item label="名称">
              {{ picture.name ?? '未命名' }}
            </a-descriptions-item>
            <a-descriptions-item label="简介">
              {{ picture.introduction ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="分类">
              {{ picture.category ?? '默认' }}
            </a-descriptions-item>
            <a-descriptions-item label="标签">
              <PictureTags :tags="picture.tags" :max="0" />
            </a-descriptions-item>
            <a-descriptions-item label="格式">
              {{ picture.picFormat ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽度">
              {{ picture.picWidth ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="高度">
              {{ picture.picHeight ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽高比">
              {{ picture.picScale ?? '-' }}
            </a-descriptions-item>
            <!-- <a-descriptions-item label="大小">
              {{ formatSize(picture.picSize) }}
            </a-descriptions-item> -->
          </a-descriptions>
          <a-space wrap>
            <a-button v-if="canEdit" type="default" @click="doEdit">
              编辑
              <template #icon>
                <EditOutlined />
              </template>
            </a-button>
            <a-button v-if="canEditImage" type="default" @click="doEditImage">
              编辑图片
              <template #icon>
                <ScissorOutlined />
              </template>
            </a-button>
            <a-button v-if="canEdit" :loading="filling" @click="doAutoFill">
              智能补充
              <template #icon>
                <ThunderboltOutlined />
              </template>
            </a-button>
            <a-button v-if="canEdit" danger @click="doDelete">
              删除
              <template #icon>
                <DeleteOutlined />
              </template>
            </a-button>
            <a-button type="primary" @click="doDownload">
              免费下载
              <template #icon>
                <DownloadOutlined />
              </template>
            </a-button>
          </a-space>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
// import { deletePictureUsingPost, getPictureVoByIdUsingGet } from '@/api/pictureController.ts'
import { message } from 'ant-design-vue'

import { useRouter } from 'vue-router'
import { getPictureVoByIdUsingGet } from '@/api/fileController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { deletePictureUsingPost } from '@/api/fileController'
import router from '@/router'
import { downloadImage } from '@/utils'
import PictureTags from '@/components/PictureTags.vue'
import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ScissorOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { autoFillPictureInfo } from '@/api-custom/pictureIndex'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController'
import { SPACE_USER_ROLE_ENUM } from '@/constants/space'
import type { SpaceVOExt } from '@/api-custom/spaceUser'

const props = defineProps<{
  id: string | number
}>()
const picture = ref<API.PictureVO>({})

// 获取图片详情
const fetchPictureDetail = async () => {
  try {
    const res = await getPictureVoByIdUsingGet({
      id: props.id,
    })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
    } else {
      message.error('获取图片详情失败，' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取图片详情失败：' + e.message)
  }
}
const loginUserStore = useLoginUserStore()

/** 当前登录用户在图片所属空间中的角色，仅空间图片需要 */
const currentSpaceRole = ref<number | undefined>(undefined)

/**
 * 查询当前用户在图片所属空间中的角色。
 * 后端 /space/get/vo 会填充 currentUserRole，前端据此控制编辑入口的显隐。
 */
const fetchSpaceRole = async () => {
  const spaceId = picture.value.spaceId
  if (!spaceId) {
    return
  }
  try {
    const res = await getSpaceVoByIdUsingGet({ id: spaceId })
    currentSpaceRole.value = (res.data.data as SpaceVOExt | undefined)?.currentUserRole
  } catch {
    // 取不到角色时按「没有空间编辑权限」处理即可，不用打扰用户
  }
}

// 是否具有编辑权限
const canEdit = computed(() => {
  const loginUser = loginUserStore.loginUser
  // 未登录不可编辑
  if (!loginUser.id) {
    return false
  }
  // 管理员可以编辑任何图片
  if (loginUser.userRole === 'admin') {
    return true
  }
  // 图片上传者本人
  const user = picture.value.user || {}
  if (loginUser.id === user.id) {
    return true
  }
  // 空间图片：还要求在该空间中具备「可编辑」及以上角色
  return (
    currentSpaceRole.value !== undefined &&
    currentSpaceRole.value >= SPACE_USER_ROLE_ENUM.EDITOR
  )
})

// 能否编辑图片内容（替换原图）：公共图库仅管理员，空间图片需要「可编辑」权限
const canEditImage = computed(() => {
  const loginUser = loginUserStore.loginUser
  if (!loginUser.id) {
    return false
  }
  if (loginUser.userRole === 'admin') {
    return true
  }
  // 公共图库的图片不允许普通用户替换原图
  if (!picture.value.spaceId) {
    return false
  }
  return (
    currentSpaceRole.value !== undefined &&
    currentSpaceRole.value >= SPACE_USER_ROLE_ENUM.EDITOR
  )
})
// 编辑
const doEdit = () => {
  router.push('/add_picture?id=' + picture.value.id)
}
// 编辑图片（裁切、旋转、缩放等图像处理）
const doEditImage = () => {
  if (!picture.value.id) {
    return
  }
  router.push('/edit_picture/' + picture.value.id)
}
// 删除
const doDelete = async () => {
  const id = picture.value.id
  if (!id) {
    return
  }
  const res = await deletePictureUsingPost({ id })
  if (res.data.code === 0) {
    message.success('删除成功')
  } else {
    message.error('删除失败')
  }
}
// 处理下载
const doDownload = () => {
  downloadImage(picture.value.url)
}

// 智能补充：让后端调用多模态模型，把为空的名称/分类/简介/标签补齐
const filling = ref(false)
const doAutoFill = async () => {
  const id = picture.value.id
  if (!id) {
    return
  }
  filling.value = true
  try {
    const res = await autoFillPictureInfo({ pictureId: id })
    if (res.data.code !== 0) {
      message.error('智能补充失败：' + res.data.message)
      return
    }
    const filledFields = res.data.data ?? []
    if (filledFields.length === 0) {
      message.info('没有需要补充的内容')
      return
    }
    message.success('智能补充完成：' + filledFields.join('、'))
    // 重新拉取详情，展示补充后的内容
    await fetchPictureDetail()
  } catch (e: any) {
    message.error('智能补充失败：' + (e?.message ?? '请稍后重试'))
  } finally {
    filling.value = false
  }
}

onMounted(async () => {
  await fetchPictureDetail()
  // 拿到图片详情后再查空间角色（需要 spaceId）
  await fetchSpaceRole()
})
</script>

<style scoped>
#pictureDetailPage {
  margin-bottom: 16px;
}
</style>

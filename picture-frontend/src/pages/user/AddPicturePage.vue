<template>
  <div id="addPicturePage">
    <h2 style="margin-bottom: 16px">
      {{ route.query?.id ? '修改图片' : '创建图片' }}
    </h2>

    <!-- 上传目标选择（放在上传前，先选目标再上传） -->
    <div style="margin-bottom: 16px">
      <p style="margin-bottom: 4px; font-weight: 500">上传至</p>
      <a-select
        v-model:value="uploadTarget"
        :options="targetOptions"
        :loading="loadingTargets"
        style="min-width: 280px"
        placeholder="请选择上传目标"
      />
      <a-typography-text v-if="spaceId" type="secondary" style="display: block; margin-top: 8px">
        保存至空间：
        <a :href="`/space/${spaceId}`" target="_blank">点击查看该空间</a>
      </a-typography-text>
    </div>

    <!-- 选择上传方式（编辑公共图库图片时普通用户不能替换原图，隐藏上传入口） -->
    <a-tabs v-if="canReplaceImage" v-model:activeKey="uploadType">
      <a-tab-pane key="file" tab="文件上传">
        <PictureUpload
          :picture="picture"
          :space-id="spaceId"
          :null-space-id="nullSpaceId"
          :onSuccess="onSuccess"
        />
      </a-tab-pane>
      <a-tab-pane key="url" tab="URL 上传" force-render>
        <UrlPictureUpload
          :picture="picture"
          :space-id="spaceId"
          :null-space-id="nullSpaceId"
          :onSuccess="onSuccess"
        />
      </a-tab-pane>
    </a-tabs>

    <a-form v-if="picture" layout="vertical" :model="pictureForm" @finish="handleSubmit">
      <a-form-item label="名称" name="name">
        <a-input v-model:value="pictureForm.name" placeholder="请输入名称" />
      </a-form-item>
      <a-form-item label="简介" name="introduction">
        <a-textarea
          v-model:value="pictureForm.introduction"
          placeholder="请输入简介"
          :rows="2"
          autoSize
          allowClear
        />
      </a-form-item>
      <a-form-item label="分类" name="category">
        <a-auto-complete
          v-model:value="pictureForm.category"
          :options="categoryOptions"
          placeholder="请输入分类"
          allowClear
        />
      </a-form-item>
      <a-form-item label="标签" name="tags">
        <a-select
          v-model:value="pictureForm.tags"
          :options="tagOptions"
          mode="tags"
          placeholder="请输入标签"
          allowClear
        />
      </a-form-item>

      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%">创建</a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { editPictureUsingPost } from '@/api/fileController'
import { message } from 'ant-design-vue'
import { useRouter, useRoute } from 'vue-router'
import { listPictureTagCategoryUsingGet } from '@/api/pictureController'
import { getPictureVoByIdUsingGet } from '@/api/fileController'
import PictureUpload from '@/components/PictureUpload.vue'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import { listMySpaceByPage } from '@/api-custom/spaceUser'
import { SPACE_TYPE_MAP, SPACE_USER_ROLE_ENUM } from '@/constants/space'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const router = useRouter()
const route = useRoute()

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})
const onSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  pictureForm.name = newPicture.name
}

const uploadType = ref<'file' | 'url'>('file')

// ──────────────────── 上传目标 ────────────────────

/** 公共图库的固定取值，其余取值是空间的 id（后端 Long 以字符串下发，原样保存） */
const PUBLIC_TARGET = 'public'
const uploadTarget = ref<string>(PUBLIC_TARGET)

/** 上传至公共图库时为 true */
const nullSpaceId = computed(() => uploadTarget.value === PUBLIC_TARGET)

/**
 * 传给上传组件的空间 id。
 * 自动生成的类型把 Long 声明成 number，但后端实际以字符串下发，
 * 这里只做类型断言、不做数值转换，避免雪花 id 精度丢失。
 */
const spaceId = computed(() =>
  uploadTarget.value === PUBLIC_TARGET ? null : (uploadTarget.value as unknown as number),
)

const loginUserStore = useLoginUserStore()

/**
 * 是否展示「替换原图」的上传入口。
 * 编辑已有图片时，公共图库的图片只允许管理员替换原图（与后端 checkPictureReuploadAuth 一致），
 * 所以普通用户进来编辑信息时不再显示上传区域，避免点了必然失败。
 */
const canReplaceImage = computed(() => {
  const currentPicture = picture.value
  // 新增图片：任何人都可以上传
  if (!currentPicture || !currentPicture.id) {
    return true
  }
  if (loginUserStore.loginUser.userRole === 'admin') {
    return true
  }
  // 空间图片可以替换（后端会再校验空间的「编辑者」权限）
  return !!currentPicture.spaceId
})

interface UploadTargetOption {
  label: string
  value: string
}

const targetOptions = ref<UploadTargetOption[]>([{ label: '公共图库', value: PUBLIC_TARGET }])
const loadingTargets = ref(false)

/** 获取可以上传的目标：公共图库 + 我有「能看能上传」及以上权限的空间 */
const fetchUploadTargets = async () => {
  loadingTargets.value = true
  try {
    const res = await listMySpaceByPage({ current: 1, pageSize: 20 })
    if (res.data.code !== 0) {
      message.error('获取空间列表失败：' + res.data.message)
      return
    }
    const spaces = (res.data.data?.records ?? []).filter(
      (space) => (space.currentUserRole ?? -1) >= SPACE_USER_ROLE_ENUM.UPLOADER,
    )
    // 私有空间排在团队空间前面
    spaces.sort((a, b) => (a.spaceType ?? 0) - (b.spaceType ?? 0))
    targetOptions.value = [
      { label: '公共图库', value: PUBLIC_TARGET },
      ...spaces.map((space) => ({
        label: `${space.spaceName}（${SPACE_TYPE_MAP[space.spaceType ?? 0]}）`,
        value: String(space.id),
      })),
    ]
  } catch (e: any) {
    message.error('获取空间列表失败：' + e.message)
  } finally {
    loadingTargets.value = false
  }
}

/**
 * 提交表单
 */
const handleSubmit = async (values: any) => {
  if (!picture.value) {
    message.error('图片信息不存在')
    return
  }
  const pictureId = picture.value.id
  if (!pictureId) {
    return
  }
  const res = await editPictureUsingPost({
    id: pictureId,
    ...(spaceId.value != null ? { spaceId: spaceId.value } : {}),
    ...values,
  })
  if (res.data.code === 0 && res.data.data) {
    message.success('创建成功')
    // 跳转到图片详情页
    router.push({
      path: `/picture/${pictureId}`,
    })
  } else {
    message.error('创建失败，' + res.data.message)
  }
}

// 定义选项的类型
interface OptionType {
  value: string
  label: string
}

const tagOptions = ref<OptionType[]>([])
const categoryOptions = ref<OptionType[]>([])

// 获取标签和分类选项
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    // 转换成下拉选项组件接受的格式
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
  } else {
    message.error('加载选项失败，' + res.data.message)
  }
}

/** 把上传目标切换到指定空间，不存在（无权限）时给出提示 */
const selectTargetIfAllowed = (targetId: unknown, tip: string) => {
  if (targetId == null || targetId === '') {
    return
  }
  const value = String(targetId)
  if (targetOptions.value.some((option) => option.value === value)) {
    uploadTarget.value = value
  } else {
    message.warning(tip)
  }
}

// 获取老数据
const getOldPicture = async () => {
  // 获取数据
  const id = route.query?.id
  if (id) {
    const res = await getPictureVoByIdUsingGet({
      // 只做类型断言，不做数值转换，避免雪花 id 精度丢失
      id: id as unknown as number,
    })
    if (res.data.code === 0 && res.data.data) {
      const data = res.data.data
      picture.value = data
      pictureForm.name = data.name
      pictureForm.introduction = data.introduction
      pictureForm.category = data.category
      pictureForm.tags = data.tags
      // 修改图片时，默认选中这张图原本所属的空间
      selectTargetIfAllowed(data.spaceId ?? PUBLIC_TARGET, '你没有该空间的上传权限')
    }
  }
}

onMounted(async () => {
  getTagCategoryOptions()
  await fetchUploadTargets()
  await getOldPicture()
  // 从空间详情页点「创建图片」进来时，URL 上会带 spaceId，直接预选该空间
  selectTargetIfAllowed(route.query?.spaceId, '你没有该空间的上传权限，已默认选择公共图库')
})
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}
</style>

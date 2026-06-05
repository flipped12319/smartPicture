<template>
  <div id="addPicturePage">
    <h2 style="margin-bottom: 16px">
      {{ route.query?.id ? '修改图片' : '创建图片' }}
    </h2>

    <!-- 上传目标选择（放在上传前，先选目标再上传） -->
    <a-radio-group
      v-model:value="uploadTarget"
      @change="handleTargetChange"
      style="margin-bottom: 16px"
    >
      <p style="margin-bottom: 4px; font-weight: 500">上传至</p>
      <a-radio value="private">私有空间</a-radio>
      <a-radio value="public">公共图库</a-radio>
    </a-radio-group>
    <a-typography-paragraph v-if="spaceId" type="secondary" style="margin-bottom: 16px">
      保存至空间：<a :href="`/space/${spaceId}`" target="_blank">{{ spaceId }}</a>
    </a-typography-paragraph>

    <!-- 选择上传方式 -->
    <a-tabs v-model:activeKey="uploadType"
      >>
      <a-tab-pane key="file" tab="文件上传">
        <PictureUpload :picture="picture" :space-id="spaceId" :null-space-id="nullSpaceId" :onSuccess="onSuccess" />
      </a-tab-pane>
      <a-tab-pane key="url" tab="URL 上传" force-render>
        <UrlPictureUpload :picture="picture" :space-id="spaceId" :null-space-id="nullSpaceId" :onSuccess="onSuccess" />
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
import { ref, reactive } from 'vue'
import { editPictureUsingPost } from '@/api/fileController'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { listPictureTagCategoryUsingGet } from '@/api/pictureController'
import { onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { getPictureVoByIdUsingGet } from '@/api/fileController'
import PictureUpload from '@/components/PictureUpload.vue'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { getSpaceIdByUserIdUsingGet } from '@/api/spaceController'
const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})
const onSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  pictureForm.name = newPicture.name
}
const router = useRouter()
const uploadType = ref<'file' | 'url'>('file')
const uploadTarget = ref<'private' | 'public'>('private')

/** 上传至公共图库时为 true */
const nullSpaceId = computed(() => uploadTarget.value === 'public')
// 空间 id
// const spaceId = computed(() => {
//   return route.query?.spaceId
// })
const spaceId = ref<number | null | undefined>(null)
let tempSpaceId = null
console.log('spaceId', spaceId.value)
console.log('tempSpaceId', tempSpaceId)

// 处理切换事件，可根据需要执行业务逻辑
const handleTargetChange = () => {
  if (uploadTarget.value === 'private') {
    console.log('用户选择了私有空间 ', tempSpaceId)
    spaceId.value = tempSpaceId
    // 私有空间处理逻辑...
  } else {
    console.log('用户选择了公共图库')
    // 公共图库处理逻辑...
    spaceId.value = null
  }
}
/**
 * 提交表单
 * @param values
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
    spaceId: spaceId.value,
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

console.log(spaceId)

onMounted(() => {
  getTagCategoryOptions()
  getOldPicture()
  getSpaceId()
})
const route = useRoute()

const getSpaceId = async () => {
  const loginUserStore = useLoginUserStore()

  // 如果 store 中还没有用户信息，先拉取
  if (!loginUserStore.loginUser.id) {
    await loginUserStore.fetchLoginUser()
  }

  const { loginUser } = loginUserStore
  if (!loginUser.id) {
    console.error('用户未登录或 id 不存在')
    return
  }
  console.log('userid', loginUser.id)
  console.log('loginUser.id 类型:', typeof loginUser.id, '值:', loginUser.id)

  const res = await getSpaceIdByUserIdUsingGet({
    userId: loginUser.id,
  })
  tempSpaceId = res.data.data
  // 仅在用户保持在「私有空间」选项时才更新 spaceId，避免覆盖用户已切换的「公共图库」选择
  if (uploadTarget.value === 'private') {
    spaceId.value = tempSpaceId
  }
  console.log('spaceId', res)
}

// 获取老数据
const getOldPicture = async () => {
  // 获取数据
  const id = route.query?.id
  if (id) {
    const res = await getPictureVoByIdUsingGet({
      id: id,
    })
    if (res.data.code === 0 && res.data.data) {
      const data = res.data.data
      picture.value = data
      pictureForm.name = data.name
      pictureForm.introduction = data.introduction
      pictureForm.category = data.category
      pictureForm.tags = data.tags
    }
  }
}
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}
</style>

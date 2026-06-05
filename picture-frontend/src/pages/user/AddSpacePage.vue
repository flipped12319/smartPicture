<template>
  <div id="addPicturePage">
    <!-- <PictureUpload :picture="picture" :onSuccess="onSuccess" /> -->
    <a-form layout="vertical" :model="formData" @finish="handleSubmit">
      <a-form-item label="空间名称" name="spaceName">
        <a-input v-model:value="formData.spaceName" placeholder="请输入空间名称" allow-clear />
      </a-form-item>
      <a-form-item label="空间级别" name="spaceLevel">
        <a-select
          v-model:value="formData.spaceLevel"
          :options="SPACE_LEVEL_OPTIONS"
          placeholder="请输入空间级别"
          style="min-width: 180px"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%" :loading="loading">
          提交
        </a-button>
      </a-form-item>
      <a-card title="空间级别介绍">
        <a-typography-paragraph>
          * 目前仅支持开通普通版，如需升级空间，请联系
          <a href="https://codefather.cn" target="_blank">程序员鱼皮</a>。
        </a-typography-paragraph>
        <a-typography-paragraph v-for="spaceLevel in spaceLevelList">
          {{ spaceLevel.text }}： 大小 {{ spaceLevel.maxSize / 1024 / 1024 }}MB， 数量
          {{ spaceLevel.maxCount }}
        </a-typography-paragraph>
      </a-card>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { SPACE_LEVEL_ENUM, SPACE_LEVEL_OPTIONS } from '@/constants/space'
import PictureUpload from '@/components/PictureUpload.vue'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import router from '@/router'
import { addSpaceUsingPost } from '@/api/spaceController'
import { onMounted } from 'vue'
import { listSpaceLevelUsingGet } from '@/api/spaceController'
const formData = reactive<API.SpaceAddRequest | API.SpaceUpdateRequest>({
  spaceName: '',
  spaceLevel: SPACE_LEVEL_ENUM.COMMON,
})
const loading = ref(false)
const handleSubmit = async (values: any) => {
  loading.value = true
  const res = await addSpaceUsingPost({
    ...formData,
  })
  if (res.data.code === 0 && res.data.data) {
    message.success('创建成功')
    router.push({
      path: `/space/${res.data.data}`,
    })
  } else {
    message.error('创建失败，' + res.data.message)
  }
  loading.value = false
}
const spaceLevelList = ref<API.SpaceLevel[]>([])

// 获取空间级别
const fetchSpaceLevelList = async () => {
  const res = await listSpaceLevelUsingGet()
  if (res.data.code === 0 && res.data.data) {
    spaceLevelList.value = res.data.data
  } else {
    message.error('加载空间级别失败，' + res.data.message)
  }
}

onMounted(() => {
  fetchSpaceLevelList()
})
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}
</style>

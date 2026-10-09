<template>
  <div id="addAlbumPage">
    <h2>创建相册</h2>
    <a-form layout="vertical" :model="form" @finish="doSubmit">
      <a-form-item
        label="相册名称"
        name="name"
        :rules="[{ required: true, message: '请输入相册名称' }]"
      >
        <a-input v-model:value="form.name" placeholder="请输入相册名称" :maxlength="64" show-count />
      </a-form-item>
      <a-form-item
        label="相册说明"
        name="introduction"
        :rules="[{ required: true, message: '请输入相册说明' }]"
      >
        <a-textarea
          v-model:value="form.introduction"
          placeholder="简单介绍一下这个相册的内容吧"
          :rows="3"
          :maxlength="512"
          show-count
        />
      </a-form-item>

      <a-form-item label="选择图片（可以来自我的空间，也可以来自公共图库）">
        <a-tabs v-model:activeKey="activeTab">
          <a-tab-pane key="space" tab="从我的空间选择">
            <AlbumPicturePicker
              v-if="spaceId"
              source="space"
              :spaceId="spaceId"
              v-model:selectedIds="selectedIds"
            />
            <a-empty v-else description="你还没有私人空间，请先创建空间" />
          </a-tab-pane>
          <a-tab-pane key="public" tab="从公共图库选择">
            <AlbumPicturePicker source="public" v-model:selectedIds="selectedIds" />
          </a-tab-pane>
        </a-tabs>
      </a-form-item>

      <a-flex justify="space-between" align="center">
        <a-typography-text type="secondary">已选 {{ selectedIds.length }} 张图片</a-typography-text>
        <a-space>
          <a-button @click="router.back()">取消</a-button>
          <a-button type="primary" html-type="submit" :loading="submitting">创建相册</a-button>
        </a-space>
      </a-flex>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { addAlbum } from '@/api-custom/album'
import { getSpaceIdByUserIdUsingGet } from '@/api/spaceController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import AlbumPicturePicker from '@/components/AlbumPicturePicker.vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

const form = reactive({
  name: '',
  introduction: '',
})
// 两个 tab 共享同一份选中结果，因此可以同时从空间和公共图库挑图
const selectedIds = ref<number[]>([])
const activeTab = ref('space')
// 后端 Long 以字符串下发，这里原样保存，避免 Number() 转换造成雪花 id 精度丢失
const spaceId = ref<string | number>()
const submitting = ref(false)

// 查询当前用户的私人空间 id
const fetchSpaceId = async () => {
  const userId = loginUserStore.loginUser.id
  if (!userId) {
    return
  }
  const res = await getSpaceIdByUserIdUsingGet({ userId })
  if (res.data.code === 0 && res.data.data) {
    spaceId.value = res.data.data
  }
}

const doSubmit = async () => {
  if (!form.name.trim()) {
    message.error('请输入相册名称')
    return
  }
  if (!form.introduction.trim()) {
    message.error('请输入相册说明')
    return
  }
  submitting.value = true
  try {
    const res = await addAlbum({
      name: form.name.trim(),
      introduction: form.introduction.trim(),
      pictureIds: selectedIds.value,
    })
    if (res.data.code === 0) {
      message.success('相册创建成功')
      const newAlbumId = res.data.data
      router.push(newAlbumId ? `/album/${newAlbumId}` : '/my_album')
    } else {
      message.error('创建相册失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('创建相册失败：' + e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  fetchSpaceId()
})
</script>

<style scoped></style>

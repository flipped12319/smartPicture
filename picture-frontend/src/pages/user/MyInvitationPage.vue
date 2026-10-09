<template>
  <div id="myInvitationPage">
    <h2>我的邀请</h2>
    <a-spin :spinning="loading">
      <a-empty v-if="invitationList.length === 0" description="暂时没有新的空间邀请" />
      <a-list v-else :data-source="invitationList" item-layout="horizontal">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta>
              <template #title>
                邀请你加入空间「{{ item.space?.spaceName ?? item.spaceId }}」
              </template>
              <template #description>
                <div>邀请人：{{ item.inviter?.userName ?? item.inviterId }}</div>
                <div>赋予权限：{{ SPACE_USER_ROLE_MAP[item.spaceRole ?? 0] }}</div>
                <div>邀请时间：{{ item.createTime }}</div>
              </template>
            </a-list-item-meta>
            <template #actions>
              <a-button type="primary" size="small" @click="doHandle(item, true)">接受</a-button>
              <a-button danger size="small" @click="doHandle(item, false)">拒绝</a-button>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </a-spin>

    <a-pagination
      style="text-align: right; margin-top: 16px"
      v-model:current="searchParams.current"
      v-model:pageSize="searchParams.pageSize"
      :total="total"
      :show-total="() => `邀请总数 ${total}`"
      @change="onPageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { SPACE_USER_ROLE_MAP } from '@/constants/space'
import { acceptInvitation, listMyInvitation, rejectInvitation } from '@/api-custom/spaceUser'
import type { SpaceUserVO } from '@/api-custom/spaceUser'

const invitationList = ref<SpaceUserVO[]>([])
const total = ref(0)
const loading = ref(true)

const searchParams = reactive({
  current: 1,
  pageSize: 10,
})

// 获取我收到的邀请
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listMyInvitation({ ...searchParams })
    if (res.data.code === 0) {
      invitationList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取邀请列表失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取邀请列表失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onPageChange = (page: number, size: number) => {
  searchParams.current = page
  searchParams.pageSize = size
  fetchData()
}

// 接受 / 拒绝邀请
const doHandle = (invitation: SpaceUserVO, agree: boolean) => {
  if (!invitation.id) {
    return
  }
  const spaceName = invitation.space?.spaceName ?? '该空间'
  Modal.confirm({
    title: agree ? '确认接受邀请' : '确认拒绝邀请',
    content: agree
      ? `接受后将按「${SPACE_USER_ROLE_MAP[invitation.spaceRole ?? 0]}」的权限加入「${spaceName}」。`
      : `确定要拒绝加入「${spaceName}」吗？`,
    okText: agree ? '接受' : '拒绝',
    okType: agree ? 'primary' : 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = agree
          ? await acceptInvitation(invitation.id as string)
          : await rejectInvitation(invitation.id as string)
        if (res.data.code === 0) {
          message.success(agree ? '已加入空间' : '已拒绝邀请')
          fetchData()
        } else {
          message.error('操作失败：' + res.data.message)
        }
      } catch (e: any) {
        message.error('操作失败：' + e.message)
      }
    },
  })
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped></style>

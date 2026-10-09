<template>
  <div id="spaceMemberPage">
    <a-flex justify="space-between" align="center">
      <h2>{{ space.spaceName }} · 成员管理</h2>
      <a-space>
        <a-button @click="router.push(`/space/${id}`)">返回空间</a-button>
        <a-button v-if="isManager" type="primary" @click="openInviteModal">+ 邀请成员</a-button>
      </a-space>
    </a-flex>

    <a-alert
      v-if="!isManager"
      type="info"
      show-icon
      style="margin: 12px 0"
      message="只有空间管理员可以邀请成员和调整权限"
    />

    <a-table
      :columns="columns"
      :data-source="memberList"
      :pagination="pagination"
      :loading="loading"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'user'">
          <a-space>
            <a-avatar :src="record.user?.userAvatar" />
            <span>{{ record.user?.userName ?? record.userId }}</span>
          </a-space>
        </template>
        <template v-else-if="column.dataIndex === 'spaceRole'">
          <a-select
            v-if="canEditRole(record)"
            :value="record.spaceRole"
            :options="SPACE_USER_ROLE_OPTIONS"
            style="width: 220px"
            @change="(value: number) => doUpdateRole(record, value)"
          />
          <a-tag v-else :color="record.spaceRole === SPACE_USER_ROLE_ENUM.MANAGER ? 'gold' : 'blue'">
            {{ SPACE_USER_ROLE_MAP[record.spaceRole ?? 0] }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'inviter'">
          {{ record.inviter?.userName ?? '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button v-if="canRemove(record)" type="link" danger @click="doRemove(record)">
            {{ isSelf(record) ? '退出空间' : '移除' }}
          </a-button>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>

    <!-- 邀请成员 -->
    <a-modal
      v-model:open="inviteModalOpen"
      title="邀请成员加入空间"
      :confirm-loading="inviting"
      @ok="doInvite"
    >
      <a-form layout="vertical" :model="inviteForm">
        <a-form-item label="用户账号" required>
          <a-input
            v-model:value="inviteForm.userAccount"
            placeholder="请输入对方的登录账号"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="赋予权限" required>
          <a-select v-model:value="inviteForm.spaceRole" :options="SPACE_USER_ROLE_OPTIONS" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController'
import {
  inviteSpaceUser,
  listSpaceUserByPage,
  removeSpaceUser,
  updateSpaceUserRole,
} from '@/api-custom/spaceUser'
import type { SpaceUserVO, SpaceVOExt } from '@/api-custom/spaceUser'
import { SPACE_USER_ROLE_ENUM, SPACE_USER_ROLE_MAP, SPACE_USER_ROLE_OPTIONS } from '@/constants/space'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const props = defineProps<{
  id: string | number
}>()

const router = useRouter()
const loginUserStore = useLoginUserStore()

const space = ref<SpaceVOExt>({})
const memberList = ref<SpaceUserVO[]>([])
const total = ref(0)
const loading = ref(true)

const inviteModalOpen = ref(false)
const inviting = ref(false)
const inviteForm = reactive({
  userAccount: '',
  spaceRole: SPACE_USER_ROLE_ENUM.VIEWER as number,
})

const columns = [
  { title: '成员', dataIndex: 'user', width: 220 },
  { title: '空间权限', dataIndex: 'spaceRole', width: 260 },
  { title: '邀请人', dataIndex: 'inviter', width: 160 },
  { title: '加入时间', dataIndex: 'createTime', width: 200 },
  { title: '操作', key: 'action', width: 120 },
]

// 当前登录用户是否为空间管理员
const isManager = computed(() => space.value.currentUserRole === SPACE_USER_ROLE_ENUM.MANAGER)

const pagination = computed(() => ({
  current: searchParams.current ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 位成员`,
}))

const searchParams = reactive({
  current: 1,
  pageSize: 10,
})

// 后端 Long 以字符串下发，比较时统一转成字符串
const isSelf = (record: SpaceUserVO) =>
  String(record.userId) === String(loginUserStore.loginUser?.id)

const canEditRole = (record: SpaceUserVO) =>
  isManager.value && record.spaceRole !== SPACE_USER_ROLE_ENUM.MANAGER

const canRemove = (record: SpaceUserVO) =>
  record.spaceRole !== SPACE_USER_ROLE_ENUM.MANAGER && (isManager.value || isSelf(record))

// 获取空间信息（用于判断当前用户是否有管理权限）
const fetchSpace = async () => {
  // 自动生成的类型把 Long 声明成 number，但后端实际以字符串下发；
  // 这里只做类型断言、不做数值转换，保证雪花 id 精度不丢失
  const res = await getSpaceVoByIdUsingGet({ id: props.id as number })
  if (res.data.code === 0 && res.data.data) {
    space.value = res.data.data as SpaceVOExt
  } else {
    message.error('获取空间信息失败：' + res.data.message)
  }
}

// 获取成员列表
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listSpaceUserByPage({
      spaceId: props.id,
      current: searchParams.current,
      pageSize: searchParams.pageSize,
    })
    if (res.data.code === 0) {
      memberList.value = res.data.data?.records ?? []
      total.value = res.data.data?.total ?? 0
    } else {
      message.error('获取成员列表失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取成员列表失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const onTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

const openInviteModal = () => {
  inviteForm.userAccount = ''
  inviteForm.spaceRole = SPACE_USER_ROLE_ENUM.VIEWER
  inviteModalOpen.value = true
}

// 邀请成员
const doInvite = async () => {
  if (!inviteForm.userAccount.trim()) {
    message.error('请输入对方的账号')
    return
  }
  inviting.value = true
  try {
    const res = await inviteSpaceUser({
      spaceId: props.id,
      userAccount: inviteForm.userAccount.trim(),
      spaceRole: inviteForm.spaceRole,
    })
    if (res.data.code === 0) {
      message.success('邀请已发送，等待对方接受')
      inviteModalOpen.value = false
    } else {
      message.error('邀请失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('邀请失败：' + e.message)
  } finally {
    inviting.value = false
  }
}

// 修改成员权限
const doUpdateRole = async (record: SpaceUserVO, spaceRole: number) => {
  if (!record.id || spaceRole === record.spaceRole) {
    return
  }
  try {
    const res = await updateSpaceUserRole({ id: record.id as string, spaceRole })
    if (res.data.code === 0) {
      message.success('权限已更新')
      fetchData()
    } else {
      message.error('修改权限失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('修改权限失败：' + e.message)
  }
}

// 移除成员 / 退出空间
const doRemove = (record: SpaceUserVO) => {
  if (!record.id) {
    return
  }
  const self = isSelf(record)
  Modal.confirm({
    title: self ? '确认退出空间' : '确认移除成员',
    content: self
      ? `退出后将无法再查看「${space.value.spaceName}」中的图片，确定吗？`
      : `确定要把「${record.user?.userName ?? record.userId}」移出空间吗？`,
    okText: self ? '退出' : '移除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await removeSpaceUser(record.id as string)
        if (res.data.code === 0) {
          message.success(self ? '已退出空间' : '已移除成员')
          if (self) {
            router.push('/my_space')
          } else {
            fetchData()
          }
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
  fetchSpace()
  fetchData()
})
</script>

<style scoped></style>

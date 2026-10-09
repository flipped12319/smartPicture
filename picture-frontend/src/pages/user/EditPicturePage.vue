<template>
  <div id="editPicturePage">
    <a-card :title="`编辑图片：${picture.name ?? '未命名'}`" :bordered="false">
      <!-- 协同编辑状态：谁能改、谁在看，都实时提示 -->
      <a-alert
        class="collab-alert"
        show-icon
        :type="isEditingMe ? 'success' : 'info'"
        :message="collabTitle"
      >
        <template #description>
          <div>{{ collabMessage }}</div>
          <div v-if="participants.length" class="collab-participants">
            <span class="collab-participants__label">当前在线：</span>
            <a-tag
              v-for="item in participants"
              :key="item.userId"
              :color="item.editing ? 'green' : 'default'"
            >
              {{ item.userName }}{{ item.editing ? '（编辑中）' : '' }}
            </a-tag>
          </div>
        </template>
      </a-alert>
      <a-space class="collab-actions">
        <a-button
          v-if="!isEditingMe"
          type="primary"
          :disabled="!collabConnected || !!editorUserId"
          @click="startEditing"
        >
          {{ editorUserId ? '有人正在编辑' : '开始编辑' }}
        </a-button>
        <a-button v-else danger @click="exitEditing">退出编辑</a-button>
        <span class="collab-tip">
          {{ isEditingMe ? '你正在编辑，其他成员可以看到你的操作' : '开始编辑后才能调整图片' }}
        </span>
      </a-space>

      <a-row :gutter="16">
        <!-- 编辑区 -->
        <a-col :xs="24" :md="17">
          <div class="editor-stage" :class="{ 'viewer-mode': !canOperate }">
            <img
              ref="imageRef"
              class="editor-image"
              :src="proxyUrl"
              crossorigin="use-credentials"
              alt="待编辑图片"
              @load="initCropper"
              @error="onImageError"
            />
          </div>
        </a-col>
        <!-- 操作区 -->
        <a-col :xs="24" :md="7">
          <a-space direction="vertical" size="middle" style="width: 100%">
            <a-divider orientation="left" style="margin: 0">变换</a-divider>
            <a-space>
              <a-button :disabled="!canOperate" @click="handleRotate(-90)">
                <template #icon>
                  <UndoOutlined />
                </template>
                左转 90°
              </a-button>
              <a-button :disabled="!canOperate" @click="handleRotate(90)">
                <template #icon>
                  <RedoOutlined />
                </template>
                右转 90°
              </a-button>
            </a-space>
            <a-space>
              <a-button :disabled="!canOperate" @click="handleZoom(0.1)">
                <template #icon>
                  <ZoomInOutlined />
                </template>
                放大
              </a-button>
              <a-button :disabled="!canOperate" @click="handleZoom(-0.1)">
                <template #icon>
                  <ZoomOutOutlined />
                </template>
                缩小
              </a-button>
            </a-space>

            <a-divider orientation="left" style="margin: 0">裁切比例</a-divider>
            <a-radio-group
              v-model:value="aspectKey"
              button-style="solid"
              :disabled="!canOperate"
              @change="handleAspectChange"
            >
              <a-radio-button v-for="item in ASPECT_OPTIONS" :key="item.value" :value="item.value">
                {{ item.label }}
              </a-radio-button>
            </a-radio-group>
            <div class="editor-tip">
              拖动图片可以调整位置，拖动裁切框的边角可以改变裁切范围。
            </div>

            <a-divider style="margin: 0" />
            <a-button block :disabled="!canOperate" @click="handleReset">重置</a-button>
            <a-button
              type="primary"
              block
              :loading="submitting"
              :disabled="!canOperate"
              @click="handleSubmit"
            >
              确认并保存
            </a-button>
            <a-button block @click="goBack">取消</a-button>
          </a-space>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  RedoOutlined,
  UndoOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons-vue'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'
import request from '@/request'
import { API_BASE, WS_BASE } from '@/config'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

/** 协同编辑消息类型，与后端 PictureEditMessageTypeEnum 一一对应 */
const EDIT_MESSAGE_TYPE = {
  /** 建立连接，加入编辑 */
  INFO: 1,
  /** 开始编辑 */
  START: 2,
  /** 编辑操作 */
  OPERATION: 3,
  /** 退出编辑状态 */
  EXIT: 4,
  /** 断开连接，离开编辑 */
  LEAVE: 5,
  /** 消息错误 */
  ERROR: 6,
} as const

/** 协同编辑参与者 */
interface EditParticipant {
  userId?: string | number
  userName?: string
  userAvatar?: string
  editing?: boolean
}

/** 一次具体的编辑动作，序列化后放在消息的 payload 里 */
interface EditOperation {
  /** 动作类型 */
  action: 'ROTATE' | 'ZOOM' | 'ASPECT' | 'RESET' | 'CROP'
  /** ROTATE 为旋转角度，ZOOM 为缩放比例，ASPECT 为裁切比例 */
  value?: number
  /** ASPECT 时用于提示的比例名称 */
  label?: string
  /** CROP 时的裁切框数据（相对原图的坐标） */
  data?: {
    x: number
    y: number
    width: number
    height: number
    rotate?: number
    scaleX?: number
    scaleY?: number
  }
}

/** 后端广播的协同编辑消息 */
interface EditCollabMessage {
  type?: number
  message?: string
  /** 触发这条消息的用户 */
  user?: EditParticipant
  editorUserId?: string | number | null
  editorUserName?: string
  participants?: EditParticipant[]
  payload?: string
  /** 新成员加入时私下补发的操作记录 */
  operationHistory?: string[]
}

/**
 * 后端统一响应结构。
 * 注意：后端把所有 Long 都序列化成了字符串（雪花 id 有 19 位），
 * 而 src/api 里自动生成的类型把它们标成了 number，与实际运行时不符，
 * 因此这里不复用生成类型，直接按字符串透传 id。
 */
interface ApiResponse<T> {
  code?: number
  message?: string
  data?: T
}

const props = defineProps<{
  id: string | number
}>()

const router = useRouter()
const picture = ref<API.PictureVO>({})
const imageRef = ref<HTMLImageElement>()
const submitting = ref(false)
const aspectKey = ref('free')

let cropper: Cropper | null = null

const loginUserStore = useLoginUserStore()

/** 协同编辑：连接状态、参与者、当前编辑者 */
const collabConnected = ref(false)
const collabMessage = ref('正在连接协同编辑...')
const participants = ref<EditParticipant[]>([])
const editorUserId = ref<string | number | null>(null)
const editorUserName = ref('')

let socket: WebSocket | null = null

/** 是否由我持有编辑权（后端保证同一时刻只有一个人能编辑） */
const isEditingMe = computed(
  () =>
    collabConnected.value &&
    editorUserId.value !== null &&
    editorUserId.value !== undefined &&
    String(editorUserId.value) === String(loginUserStore.loginUser.id),
)

/** 是否允许操作画布：必须自己持有编辑权 */
const canOperate = computed(() => isEditingMe.value)

/** 顶部状态提示 */
const collabTitle = computed(() => {
  if (!collabConnected.value) {
    return '协同编辑连接中...'
  }
  if (isEditingMe.value) {
    return '你正在编辑这张图片'
  }
  if (editorUserId.value !== null && editorUserId.value !== undefined) {
    return `${editorUserName.value || '其他成员'} 正在编辑这张图片`
  }
  return '当前没有人在编辑，点击「开始编辑」即可修改'
})

/** 发送一条协同编辑消息 */
const sendCollabMessage = (type: number, payload?: string) => {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return false
  }
  socket.send(JSON.stringify({ type, payload }))
  return true
}

/** 解析操作内容 */
const parseOperation = (payload?: string): EditOperation | null => {
  if (!payload) {
    return null
  }
  try {
    const operation = JSON.parse(payload)
    return operation && typeof operation.action === 'string' ? operation : null
  } catch {
    return null
  }
}

/** 把一次编辑动作应用到本地画布，让画面和编辑者保持一致 */
const applyOperation = (operation: EditOperation) => {
  if (!cropper) {
    return
  }
  switch (operation.action) {
    case 'ROTATE':
      cropper.rotate(operation.value ?? 0)
      break
    case 'ZOOM':
      cropper.zoom(operation.value ?? 0)
      break
    case 'ASPECT':
      cropper.setAspectRatio(operation.value ?? Number.NaN)
      aspectKey.value =
        ASPECT_OPTIONS.find((item) => item.ratio === operation.value)?.value ?? 'free'
      break
    case 'RESET':
      cropper.reset()
      aspectKey.value = 'free'
      cropper.setAspectRatio(Number.NaN)
      break
    case 'CROP':
      if (operation.data) {
        // 裁切框用「相对原图」的绝对坐标同步，不受各自内部缩放状态影响
        cropper.setData(operation.data)
      }
      break
    default:
      break
  }
}

/** 回放操作记录：新加入的人据此把画布追平到编辑者当前的状态 */
const replayOperations = (operations: string[]) => {
  operations.forEach((payload) => {
    const operation = parseOperation(payload)
    if (operation) {
      applyOperation(operation)
    }
  })
}

/** 把编辑动作用一句人话描述出来，便于提示 */
const describeOperation = (operation: EditOperation): string => {
  switch (operation.action) {
    case 'ROTATE':
      return (operation.value ?? 0) > 0 ? '向右旋转了 90°' : '向左旋转了 90°'
    case 'ZOOM':
      return (operation.value ?? 0) > 0 ? '放大了图片' : '缩小了图片'
    case 'ASPECT':
      return `把裁切比例切换为「${operation.label ?? '自由'}」`
    case 'RESET':
      return '重置了编辑'
    case 'CROP':
      return '调整了裁切范围'
    default:
      return '调整了图片'
  }
}

/** 发送一次编辑动作 */
const sendOperation = (operation: EditOperation) => {
  sendCollabMessage(EDIT_MESSAGE_TYPE.OPERATION, JSON.stringify(operation))
}

/** 处理服务端广播：其他人的状态变化会在页面上实时提示，画布也会跟着变 */
const handleCollabMessage = (raw: string) => {
  let data: EditCollabMessage
  try {
    data = JSON.parse(raw)
  } catch {
    return
  }
  editorUserId.value = data.editorUserId ?? null
  editorUserName.value = data.editorUserName ?? ''
  participants.value = data.participants ?? []
  if (data.message) {
    collabMessage.value = data.message
  }
  // 新成员加入时会私下补发操作记录，回放一遍即可追平画布
  if (data.operationHistory?.length) {
    replayOperations(data.operationHistory)
  }
  if (data.type === EDIT_MESSAGE_TYPE.OPERATION) {
    const operation = parseOperation(data.payload)
    if (operation) {
      applyOperation(operation)
      const operatorName = data.user?.userName ?? '其他成员'
      message.info(`${operatorName} ${describeOperation(operation)}`)
    } else {
      message.info(data.message ?? '其他成员调整了图片')
    }
  } else if (data.type === EDIT_MESSAGE_TYPE.ERROR) {
    message.error(data.message ?? '协同编辑消息有误')
  }
}

/**
 * 登录 token（从 localStorage 取，登录成功时由 request.ts 所在流程写入）。
 *
 * 这个页面上有**两处请求不是 axios 发的**，都带不了 `Authorization` 头，只能把 token 放进 URL：
 *   1. 协同编辑的 WebSocket 握手（浏览器不允许给 WS 握手加自定义头）；
 *   2. 原图代理 `<img src="/api/picture/proxy?...">`（`<img>` 只能带 Cookie）。
 * 所以这里统一定义一份，避免两处各写一遍、漏掉一处就报「未登录」。
 */
const authToken = computed(() => localStorage.getItem('auth_token') || '')

/** 建立 WebSocket 连接（握手阶段后端会校验权限，没权限会被拒绝） */
const connectCollaboration = () => {
  // token 通过查询参数传给后端（后端在握手阶段据此识别用户）
  socket = new WebSocket(
    `${WS_BASE}/api/ws/picture/edit?pictureId=${props.id}&token=${encodeURIComponent(authToken.value)}`,
  )
  socket.onopen = () => {
    collabConnected.value = true
  }
  socket.onmessage = (event) => handleCollabMessage(event.data)
  socket.onerror = () => {
    collabConnected.value = false
  }
  socket.onclose = () => {
    collabConnected.value = false
    collabMessage.value = '协同编辑连接已断开，请刷新页面重试'
  }
}

/** 开始编辑：抢占编辑权 */
const startEditing = () => {
  if (!sendCollabMessage(EDIT_MESSAGE_TYPE.START)) {
    message.warning('协同编辑连接不可用，请刷新页面重试')
  }
}

/** 退出编辑状态 */
const exitEditing = () => {
  sendCollabMessage(EDIT_MESSAGE_TYPE.EXIT)
}

/**
 * 通过后端代理读取原图。
 * COS 桶没有配置跨域规则，直接用它加载图片后再画到 canvas 会被标记为「已污染」，
 * toBlob() 直接抛异常，编辑结果就导不出来。
 *
 * ⚠️ 必须把 token 放进查询参数：这个请求是 <img> 发起的，**只能带 Cookie、带不了
 * Authorization 头**（`crossorigin="use-credentials"` 也只管 Cookie）。而阶段 3a 之后
 * 登录态只有 JWT —— 于是「团队空间里点编辑图片」会报「未登录」。
 * 后端 `UserServiceImpl.getLoginUser` 为此专门支持了 `token` 查询参数。
 */
const proxyUrl = computed(
  () => `${API_BASE}/api/picture/proxy?id=${props.id}&token=${encodeURIComponent(authToken.value)}`
)

const ASPECT_OPTIONS = [
  { label: '自由', value: 'free', ratio: Number.NaN },
  { label: '1:1', value: '1:1', ratio: 1 },
  { label: '16:9', value: '16:9', ratio: 16 / 9 },
  { label: '4:3', value: '4:3', ratio: 4 / 3 },
]

/** 获取图片信息，保存时用于回填 id、spaceId */
const fetchPicture = async () => {
  try {
    const res = await request<ApiResponse<API.PictureVO>>('/api/get/vo', {
      method: 'GET',
      params: { id: props.id },
    })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
    } else {
      message.error('获取图片信息失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('获取图片信息失败：' + (e?.message ?? '请稍后重试'))
  }
}

/** 图片加载完成后初始化裁剪器（只初始化一次） */
const initCropper = () => {
  if (!imageRef.value || cropper) {
    return
  }
  cropper = new Cropper(imageRef.value, {
    // 限制裁切框不能超出图片范围
    viewMode: 1,
    // 持有编辑权时拖动图片本身；观看模式下禁止拖动，避免误操作
    dragMode: canOperate.value ? 'move' : 'none',
    autoCropArea: 1,
    background: false,
    responsive: true,
    // 跨域取图由 <img> 上的 crossorigin 属性负责，这里关掉 cropperjs 自己的跨域处理
    checkCrossOrigin: false,
    // 拖拽结束后把裁切框同步给其他人，让他们的画布一起变
    cropend: () => {
      if (!canOperate.value || !cropper) {
        return
      }
      const data = cropper.getData(true)
      if (data) {
        sendOperation({ action: 'CROP', data })
      }
    },
  })
}

const onImageError = () => {
  message.error('图片加载失败，请确认图片是否存在、或当前账号是否有查看权限')
}

const handleRotate = (degree: number) => {
  if (!canOperate.value) {
    return
  }
  cropper?.rotate(degree)
  sendOperation({ action: 'ROTATE', value: degree })
}

const handleZoom = (ratio: number) => {
  if (!canOperate.value) {
    return
  }
  cropper?.zoom(ratio)
  sendOperation({ action: 'ZOOM', value: ratio })
}

const handleAspectChange = () => {
  if (!canOperate.value) {
    return
  }
  const option = ASPECT_OPTIONS.find((item) => item.value === aspectKey.value)
  cropper?.setAspectRatio(option ? option.ratio : Number.NaN)
  sendOperation({ action: 'ASPECT', value: option?.ratio ?? Number.NaN, label: option?.label })
}

const handleReset = () => {
  if (!canOperate.value) {
    return
  }
  cropper?.reset()
  aspectKey.value = 'free'
  cropper?.setAspectRatio(Number.NaN)
  sendOperation({ action: 'RESET' })
}

// 一旦编辑权发生变化（拿到或失去），立刻切换画布的可拖动状态
watch(canOperate, (operable) => {
  cropper?.setDragMode(operable ? 'move' : 'none')
})

const goBack = () => {
  router.back()
}

/** 导出画布并重新上传：后端会替换原图、同步图片信息与空间额度 */
const handleSubmit = async () => {
  if (!canOperate.value) {
    message.warning('请先点击「开始编辑」再保存')
    return
  }
  if (!cropper) {
    message.error('图片还没有加载完成，请稍后再试')
    return
  }
  submitting.value = true
  try {
    const canvas = cropper.getCroppedCanvas({
      maxWidth: 4096,
      maxHeight: 4096,
      imageSmoothingEnabled: true,
      imageSmoothingQuality: 'high',
    })
    if (!canvas) {
      message.error('导出图片失败，请重试')
      return
    }
    // png 保留透明通道，其余统一用 jpeg 控制体积
    const isPng = (picture.value.picFormat ?? '').toLowerCase() === 'png'
    const mimeType = isPng ? 'image/png' : 'image/jpeg'
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob((result) => resolve(result), mimeType, 0.92),
    )
    if (!blob) {
      message.error('导出图片失败，请重试')
      return
    }
    const suffix = isPng ? 'png' : 'jpg'
    const file = new File([blob], `edited_${picture.value.id}.${suffix}`, { type: mimeType })

    // 带上 id 表示「重新上传」：后端会替换原图并同步图片信息
    const formData = new FormData()
    formData.append('file', file)
    formData.append('id', String(picture.value.id))
    const spaceId = picture.value.spaceId
    if (spaceId !== undefined && spaceId !== null) {
      // 不传 spaceId 时后端会自动复用原图片所属的空间
      formData.append('spaceId', String(spaceId))
    }
    const res = await request<ApiResponse<API.PictureVO>>('/api/upload', {
      method: 'POST',
      data: formData,
    })
    if (res.data.code === 0) {
      message.success('保存成功')
      // 保存完主动退出编辑状态，让别人可以接着改
      sendCollabMessage(EDIT_MESSAGE_TYPE.EXIT)
      router.replace(`/picture/${picture.value.id}`)
    } else {
      message.error('保存失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('保存失败：' + (e?.message ?? '请稍后重试'))
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  cropper?.destroy()
  cropper = null
  // 关闭连接，服务端会据此广播「离开编辑」并释放编辑权
  socket?.close()
  socket = null
})

fetchPicture()
connectCollaboration()
</script>

<style scoped>
#editPicturePage {
  margin-bottom: 16px;
}

.editor-stage {
  height: 560px;
  background: #f5f5f5;
  border-radius: 6px;
  overflow: hidden;
}

.editor-image {
  display: block;
  max-width: 100%;
}

/**
 * 观看模式（没有编辑权）：隐藏裁切框的拖拽把手与可拖拽面，
 * 只保留裁切范围的显示，避免其他人误拖动导致画面和编辑者不一致
 */
.editor-stage.viewer-mode :deep(.cropper-point),
.editor-stage.viewer-mode :deep(.cropper-line),
.editor-stage.viewer-mode :deep(.cropper-face) {
  display: none;
}

.editor-tip {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  line-height: 1.6;
}

.collab-alert {
  margin-bottom: 12px;
}

.collab-participants {
  margin-top: 4px;
}

.collab-participants__label {
  margin-right: 4px;
  color: rgba(0, 0, 0, 0.45);
}

.collab-actions {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.collab-tip {
  margin-left: 8px;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}
</style>

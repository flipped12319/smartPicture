<template>
  <div class="flex flex-col h-full">
    <!-- 顶部标题栏 -->
    <header
      class="flex items-center gap-3 px-4 py-3 border-b border-gray-100 bg-white shadow-sm sticky top-0 z-10"
    >
      <div
        class="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center text-white text-lg"
      >
        🤖
      </div>
      <div class="flex-1">
        <h1 class="text-base font-semibold text-gray-800">智能图库助手</h1>
        <p class="text-xs text-gray-400">支持搜索、上传、管理图片</p>
      </div>
      <!-- 操作按钮 -->
      <div class="flex items-center gap-2">
        <!-- 按钮1：清空聊天记录（彻底删除，刷新/重登录不会恢复） -->
        <a-tooltip title="清空前端聊天记录，session_id 不变，后端 Agent 记录不受影响">
          <button
            class="text-xs px-2.5 py-1.5 rounded-lg bg-red-50 text-red-500 hover:bg-red-100 hover:text-red-600 transition-colors whitespace-nowrap"
            @click="clearChatPermanently"
          >
            清空聊天记录
          </button>
        </a-tooltip>
        <!-- 按钮2：隐藏/显示聊天记录切换 -->
        <button
          v-if="hiddenBefore === null"
          class="text-xs px-2.5 py-1.5 rounded-lg bg-gray-100 text-gray-500 hover:bg-gray-200 hover:text-gray-700 transition-colors whitespace-nowrap"
          @click="hideHistory"
        >
          隐藏聊天记录
        </button>
        <button
          v-else
          class="text-xs px-2.5 py-1.5 rounded-lg bg-blue-50 text-blue-500 hover:bg-blue-100 hover:text-blue-600 transition-colors whitespace-nowrap"
          @click="showHistory"
        >
          显示聊天记录
        </button>
      </div>
    </header>

    <!-- 消息列表区域 -->
    <main ref="messageContainer" class="flex-1 overflow-y-auto px-4 py-6 space-y-5 bg-gray-50">
      <!-- 空状态 -->
      <div
        v-if="visibleMessages.length === 0"
        class="flex flex-col items-center justify-center h-full text-gray-400"
      >
        <div class="text-6xl mb-4">🤖</div>
        <p class="text-lg font-medium mb-1">欢迎使用智能图库助手</p>
        <p class="text-sm">你可以向我提问、搜索图片或上传图片</p>
      </div>

      <!-- 消息气泡 -->
      <div
        v-for="msg in visibleMessages"
        :key="msg.id"
        :class="['flex gap-3', msg.role === 'user' ? 'justify-end' : 'justify-start']"
      >
        <!-- 助手头像（左侧消息） -->
        <div
          v-if="msg.role === 'assistant'"
          class="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center text-white text-sm shrink-0"
        >
          🤖
        </div>

        <!-- 气泡内容 -->
        <div :class="['max-w-[75%]', msg.role === 'user' ? 'items-end' : 'items-start']">
          <!-- 气泡卡片 -->
          <div
            :class="[
              'rounded-2xl px-4 py-3 shadow-sm break-words',
              msg.role === 'user'
                ? 'bg-blue-500 text-white rounded-br-md'
                : 'bg-white text-gray-800 rounded-bl-md border border-gray-100',
            ]"
          >
            <!-- 文本内容 -->
            <div
              v-if="msg.content"
              :class="msg.role === 'user' ? 'text-white' : 'text-gray-800'"
              style="white-space: pre-wrap; word-break: break-word"
            >
              {{ msg.content }}
            </div>

            <!-- 消息中的图片 -->
            <div v-if="msg.images && msg.images.length > 0" class="mt-2 flex flex-wrap gap-2">
              <img
                v-for="(img, idx) in msg.images"
                :key="idx"
                :src="img"
                alt="attachment"
                class="max-w-48 max-h-48 rounded-lg object-cover cursor-pointer hover:opacity-90 transition-opacity"
                @click="previewImage(img)"
              />
            </div>
          </div>

          <!-- 时间戳 -->
          <div
            :class="[
              'text-xs text-gray-400 mt-1 px-1',
              msg.role === 'user' ? 'text-right' : 'text-left',
            ]"
          >
            {{ formatTime(msg.timestamp) }}
          </div>
        </div>

        <!-- 用户头像（右侧消息） -->
        <div
          v-if="msg.role === 'user'"
          class="w-8 h-8 rounded-lg bg-gray-200 flex items-center justify-center text-gray-500 shrink-0"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
            />
          </svg>
        </div>
      </div>

      <!-- 加载状态："正在思考..." -->
      <div v-if="isLoading" class="flex gap-3 justify-start">
        <div
          class="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-purple-600 flex items-center justify-center text-white text-sm shrink-0"
        >
          🤖
        </div>
        <div class="bg-white rounded-2xl rounded-bl-md px-4 py-3 shadow-sm border border-gray-100">
          <div class="flex items-center gap-1.5">
            <span
              class="w-2 h-2 bg-blue-400 rounded-full animate-bounce"
              style="animation-delay: 0ms"
            ></span>
            <span
              class="w-2 h-2 bg-blue-400 rounded-full animate-bounce"
              style="animation-delay: 150ms"
            ></span>
            <span
              class="w-2 h-2 bg-blue-400 rounded-full animate-bounce"
              style="animation-delay: 300ms"
            ></span>
          </div>
        </div>
      </div>
    </main>

    <!-- 底部输入区域 -->
    <footer class="border-t border-gray-100 bg-white px-4 py-3">
      <!-- 待发送图片预览 -->
      <div v-if="pendingImages.length > 0" class="flex flex-wrap gap-2 mb-3">
        <div
          v-for="(img, idx) in pendingImages"
          :key="idx"
          class="relative group w-16 h-16 rounded-lg overflow-hidden border border-gray-200"
        >
          <img :src="img" alt="preview" class="w-full h-full object-cover" />
          <button
            class="absolute -top-1 -right-1 w-5 h-5 bg-red-500 text-white rounded-full text-xs flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity shadow"
            @click="removePendingImage(idx)"
          >
            ×
          </button>
        </div>
      </div>

      <!-- 输入框 + 操作按钮 -->
      <div class="flex items-end gap-2">
        <!-- 图片上传按钮 -->
        <label
          class="w-9 h-9 flex items-center justify-center rounded-lg bg-gray-50 hover:bg-gray-100 text-gray-500 hover:text-blue-500 cursor-pointer transition-colors shrink-0"
          title="上传图片"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
            />
          </svg>
          <input
            type="file"
            accept="image/jpeg,image/png,image/gif,image/webp"
            multiple
            class="hidden"
            @change="handleFileSelect"
          />
        </label>

        <!-- 文本输入框 -->
        <textarea
          ref="textInput"
          v-model="inputText"
          class="flex-1 resize-none rounded-xl border border-gray-200 bg-gray-50 px-4 py-2.5 text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:border-blue-400 focus:bg-white focus:ring-1 focus:ring-blue-100 transition-all"
          :rows="inputRows"
          placeholder="输入消息，Enter发送 / Shift+Enter换行..."
          :disabled="isLoading"
          @keydown="handleKeydown"
          @input="adjustRows"
        ></textarea>

        <!-- 发送按钮 -->
        <button
          class="w-9 h-9 flex items-center justify-center rounded-lg shrink-0 transition-all"
          :class="
            canSend && !isLoading
              ? 'bg-blue-500 text-white hover:bg-blue-600 shadow-sm active:scale-95'
              : 'bg-gray-100 text-gray-400 cursor-not-allowed'
          "
          :disabled="!canSend || isLoading"
          @click="handleSend"
        >
          <svg
            v-if="!isLoading"
            class="w-5 h-5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
            />
          </svg>
          <svg
            v-else
            class="w-5 h-5 animate-spin"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <circle
              class="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              stroke-width="4"
            ></circle>
            <path
              class="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
            ></path>
          </svg>
        </button>
      </div>
    </footer>

    <!-- 图片预览弹窗 -->
    <Teleport to="body">
      <div
        v-if="previewImageUrl"
        class="fixed inset-0 z-50 bg-black/80 flex items-center justify-center p-8 cursor-pointer"
        @click="previewImageUrl = null"
      >
        <img
          :src="previewImageUrl"
          alt="预览"
          class="max-w-full max-h-full object-contain rounded-lg shadow-2xl"
        />
        <button
          class="absolute top-4 right-4 w-10 h-10 bg-white/20 hover:bg-white/40 text-white rounded-full flex items-center justify-center text-xl transition-colors"
          @click.stop="previewImageUrl = null"
        >
          ×
        </button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { chatUsingPost } from '@/api/aiController'
import { getSpaceIdByUserIdUsingGet } from '@/api/spaceController'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

// ==================== 内部消息模型 ====================

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  images?: string[]
  timestamp: number
}

// ==================== 会话 ID ====================

const loginUserStore = useLoginUserStore()

/**
 * 会话 ID 直接使用当前登录用户的 ID（对应 LangGraph 的 thread_id）。
 * 同一用户的对话历史跨设备、跨标签页保持一致。
 * 未登录时回退为 "guest"。
 */
const sessionId = computed(() => {
  if (loginUserStore.loginUser?.id) {
    return String(loginUserStore.loginUser.id)
  }
  return 'guest'
})

// ==================== token 与 spaceId ====================

/** 从登录时存入 localStorage 的 token */
const authToken = computed(() => localStorage.getItem('auth_token') || '')

/** 当前用户的空间 ID（可能为 null，表示公共图库） */
const spaceId = ref<string | null>(null)

/** 页面初始化时获取用户空间 ID */
async function fetchSpaceId() {
  const userId = loginUserStore.loginUser?.id
  if (!userId) return
  try {
    const res = await getSpaceIdByUserIdUsingGet({ userId })
    if (res.data.code === 0 && res.data.data != null) {
      spaceId.value = String(res.data.data)
    }
  } catch (e) {
    console.error('获取空间 ID 失败', e)
  }
}

// ==================== sessionStorage 持久化 ====================

/** 按用户隔离存储 key */
const chatStorageKey = computed(() => `ai_chat_messages_${sessionId.value}`)

/** 是否正在从 storage 恢复消息（防止恢复时触发保存） */
const restoring = false

/** 从 sessionStorage 恢复聊天记录 */
function loadMessages(): ChatMessage[] {
  try {
    const raw = sessionStorage.getItem(chatStorageKey.value)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) return parsed as ChatMessage[]
    }
  } catch (e) {
    console.error('恢复聊天记录失败', e)
  }
  return []
}

/** 将聊天记录保存到 sessionStorage */
function saveMessages() {
  if (restoring) return
  try {
    sessionStorage.setItem(chatStorageKey.value, JSON.stringify(messages.value))
  } catch (e) {
    console.error('保存聊天记录失败', e)
  }
}

// ==================== 状态 ====================

/** 消息列表（从 sessionStorage 恢复） */
const messages = ref<ChatMessage[]>(loadMessages())

/** 隐藏此时间戳之前的消息（null = 全部显示） */
const hiddenBefore = ref<number | null>(null)

/** 当前可见的消息列表 */
const visibleMessages = computed(() => {
  if (hiddenBefore.value === null) return messages.value
  return messages.value.filter((m) => m.timestamp >= hiddenBefore.value!)
})

/** 清空聊天记录：彻底清除前端消息列表，防止聊天框越来越长。
 *  仅清空前端展示和 sessionStorage 缓存，session_id 不变，后端 agent 记录不受影响。 */
function clearChatPermanently() {
  messages.value = []
  hiddenBefore.value = null
}

/** 隐藏聊天记录：将当前所有消息隐藏，新消息不受影响 */
function hideHistory() {
  hiddenBefore.value = Date.now()
}

/** 显示聊天记录：恢复显示全部历史消息 */
function showHistory() {
  hiddenBefore.value = null
}

/** 输入框文本 */
const inputText = ref('')

/** 待发送的图片（完整的 data:image/...;base64 字符串，用于本地预览） */
const pendingImages = ref<string[]>([])

/** 是否正在等待助手回复 */
const isLoading = ref(false)

/** 图片预览 URL（非空时显示弹窗） */
const previewImageUrl = ref<string | null>(null)

/** 输入框 DOM 引用 */
const textInput = ref<HTMLTextAreaElement | null>(null)

/** 消息容器 DOM 引用 */
const messageContainer = ref<HTMLElement | null>(null)

// ==================== 计算属性 ====================

/** 是否可以发送（有文本或图片，且未在加载中） */
const canSend = computed(() => {
  return (inputText.value.trim().length > 0 || pendingImages.value.length > 0) && !isLoading.value
})

/** 动态行数 */
const inputRows = ref(1)
const MAX_ROWS = 5

// ==================== 工具函数 ====================

/** 生成唯一 ID */
function generateId(): string {
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9)
}

/** 格式化时间戳为 HH:mm */
function formatTime(ts: number): string {
  const d = new Date(ts)
  return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0')
}

/**
 * 将 data:image/...;base64,... 转换为纯 base64 字符串（去掉协议前缀），
 * 后端只需要裸 base64。
 */
function stripBase64Prefix(dataUrl: string): string {
  return dataUrl.split(',')[1] ?? dataUrl
}

// ==================== 消息操作 ====================

/** 添加一条消息 */
function addMessage(role: 'user' | 'assistant', content: string, images?: string[]) {
  messages.value.push({
    id: generateId(),
    role,
    content,
    images: images ?? [],
    timestamp: Date.now(),
  })
}

/** 自动滚动到底部 */
async function scrollToBottom() {
  await nextTick()
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

// ==================== 发送逻辑 ====================

/** 发送消息 */
async function handleSend() {
  if (!canSend.value || isLoading.value) return

  const text = inputText.value.trim()
  // 保留完整的 data: URL 用于本地消息展示
  const displayImages = [...pendingImages.value]
  // 提取纯 base64 发送给后端
  const rawImages = pendingImages.value.map(stripBase64Prefix)

  // 添加用户消息到列表（展示用完整 data: URL）
  addMessage('user', text, displayImages)

  // 清空输入
  inputText.value = ''
  pendingImages.value = []
  adjustRows()
  await scrollToBottom()

  // 发送请求
  isLoading.value = true
  try {
    const requestBody: API.AiChatRequest = {
      session_id: sessionId.value,
      message: text,
      images: rawImages,
      token: authToken.value,
      spaceId: spaceId.value ?? undefined,
    }

    const res = await chatUsingPost(requestBody, { timeout: 300000 })
    console.log('requestBody ', requestBody)
    console.log('responseBody ', res)

    if (res.data.code === 0 && res.data.data) {
      const data = res.data.data as any
      const reply = data.reply
      // 兼容后端返回 images 或 image_urls 两种字段名
      const replyImages: string[] = data.images ?? data.image_urls ?? []
      addMessage('assistant', reply ?? '', replyImages)
    } else {
      message.error('请求失败：' + (res.data.message || '未知错误'))
      addMessage('assistant', `抱歉，请求失败：${res.data.message || '服务端错误'}`)
    }
  } catch (err: any) {
    console.error('发送消息失败', err)
    message.error('网络错误，请稍后重试')
    addMessage('assistant', `抱歉，网络错误：${err.message || '请检查网络连接后重试'}`)
  } finally {
    isLoading.value = false
    await scrollToBottom()
  }

  // 重新聚焦输入框
  textInput.value?.focus()
}

// ==================== 键盘处理 ====================

function handleKeydown(e: KeyboardEvent) {
  // Enter 发送（不含 Shift）
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

// ==================== 输入框自适应行数 ====================

function adjustRows() {
  const lines = inputText.value.split('\n').length
  inputRows.value = Math.min(Math.max(lines, 1), MAX_ROWS)
}

// ==================== 图片处理 ====================

/** 选择本地图片 */
function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files) return

  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    if (!file.type.startsWith('image/')) continue

    const reader = new FileReader()
    reader.onload = () => {
      pendingImages.value.push(reader.result as string)
    }
    reader.readAsDataURL(file)
  }

  // 重置 input，允许重复选择同一文件
  input.value = ''
}

/** 移除待发送图片 */
function removePendingImage(idx: number) {
  pendingImages.value.splice(idx, 1)
}

/** 点击图片放大预览 */
function previewImage(url: string) {
  previewImageUrl.value = url
}

// ==================== 自动保存 ====================

watch(messages, saveMessages, { deep: true })

// ==================== 生命周期 ====================

onMounted(async () => {
  // 获取用户空间 ID
  await fetchSpaceId()
  // 恢复历史消息后滚动到底部
  if (visibleMessages.value.length > 0) {
    await scrollToBottom()
  }
  textInput.value?.focus()
})
</script>

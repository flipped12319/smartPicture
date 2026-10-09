import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getLoginUserUsingGet } from '@/api/userController'
export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>({
    userName: '未登录',
  })

  // 标记首次用户信息是否已加载完毕，防止页面刷新时闪现未登录状态
  const initialized = ref(false)
  // 防止并发重复请求
  let fetchPromise: Promise<void> | null = null

  async function fetchLoginUser() {
    // 如果已有正在进行的请求，直接复用，避免重复调用
    if (fetchPromise) {
      return fetchPromise
    }

    fetchPromise = (async () => {
      try {
        const res = await getLoginUserUsingGet()
        if (res.data.code === 0 && res.data.data) {
          loginUser.value = res.data.data
        }
      } finally {
        initialized.value = true
        fetchPromise = null
      }
    })()

    return fetchPromise
  }

  function setLoginUser(newLoginUser: any) {
    loginUser.value = newLoginUser
  }

  return { loginUser, setLoginUser, fetchLoginUser, initialized }
})

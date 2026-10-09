import axios from 'axios'
import { message } from 'ant-design-vue'
import { API_BASE } from '@/config'

// 创建 Axios 实例
const myAxios = axios.create({
  // 统一走网关（见 src/config.ts），可通过 VITE_API_BASE 回退到直连单体
  baseURL: API_BASE,
  timeout: 60000,
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
    // 阶段 3：统一带上 JWT。
    // 登录态以前只靠 JSESSIONID Cookie，而 Session 存在单个实例的内存里 ——
    // 多实例部署时同一个 Cookie 落到不同实例会认不出来（实测两实例下 8 次请求 4 次报未登录）。
    // 让每个请求都带 Authorization: Bearer <token>，登录态就与落在哪个实例无关了。
    const token = localStorage.getItem('auth_token')
    if (token) {
      config.headers = config.headers ?? {}
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  function (error) {
    // Do something with request error
    return Promise.reject(error)
  },
)

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        // 使用 replace 避免产生多余的历史记录
        window.location.replace(`/user/login?redirect=${window.location.pathname}`)
      }
    }
    return response
  },
  function (error) {
    // 过载：服务端限流（429）或线程池耗尽（503）时，提示"繁忙"而不是当成故障。
    // 这类错误重试就会好，跟真正的报错要区分开。
    const status = error?.response?.status
    const serverMessage = error?.response?.data?.message
    if (status === 429 || status === 503) {
      message.warning(serverMessage || '当前使用人数较多，请稍后重试')
    } else if (status === 500 && serverMessage) {
      message.error(serverMessage)
    }
    return Promise.reject(error)
  },
)

export default myAxios

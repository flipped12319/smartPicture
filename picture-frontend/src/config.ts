/**
 * 后端地址统一配置
 *
 * 阶段 1 起，浏览器请求统一走网关（默认 9000），不再直连单体 8123。
 *
 * 为什么单独抽一个文件：`<img>`、`new WebSocket()` 这类浏览器原生请求
 * 不经过 axios 的 baseURL，必须自己拼完整地址；以前这些地址散落在各个页面里
 * 各写一份 `http://localhost:8123`，改一次要翻好几个文件。
 *
 * 回退到直连单体（任选其一，都不需要改代码）：
 *   1) 在 picture-frontend/.env.local 中设置 VITE_API_BASE=http://localhost:8123
 *   2) 或不设置该变量，只把下面的默认值改回 8123
 */
// 用 || 而不是 ??：环境变量被显式设为空串时也应回退到默认值
export const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:9000'

/** WebSocket 基地址：把 http(s) 换成 ws(s)，供协同编辑使用 */
export const WS_BASE = API_BASE.replace(/^http/, 'ws')

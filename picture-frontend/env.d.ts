/// <reference types="vite/client" />

/**
 * 前端可用的环境变量声明（Vite 约定：只有 VITE_ 前缀的变量会注入到客户端）
 */
interface ImportMetaEnv {
  /**
   * 后端统一入口地址。
   * 默认走网关 http://localhost:9000；设为 http://localhost:8123 可回退到直连单体。
   */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

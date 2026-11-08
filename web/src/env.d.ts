/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 网关地址，缺省 http://127.0.0.1:8080（vite dev/preview 代理目标） */
  readonly VITE_GATEWAY?: string
  /** 登录页租户候选，逗号分隔，缺省 DEMO */
  readonly VITE_TENANT_CODES?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

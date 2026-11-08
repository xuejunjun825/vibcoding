import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import App from './App.vue'
import router from './router'
import { setNavigationHandler } from './api/client'

/**
 * 入口：先装 pinia（守卫里要用 store），再装 router（守卫注册在 router/index.ts），
 * 最后把 router.push 注入 api client —— 401/403 的跳转由 client 触发，
 * 但 client 不 import router，避免 api → router → views → api 的循环依赖。
 */
const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

setNavigationHandler((to) => {
  void router.push(to)
})

app.mount('#app')

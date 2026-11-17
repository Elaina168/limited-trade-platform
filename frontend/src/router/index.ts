import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth.ts'
import AppLayout from '../layouts/AppLayout.vue'

declare module 'vue-router' {
  interface RouteMeta { requiresAuth?: boolean; requiresAdmin?: boolean; title?: string }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/unavailable', name: 'unavailable', component: () => import('../views/UnavailableView.vue'), meta: { title: '连接服务' } },
    {
      path: '/', component: AppLayout, meta: { requiresAuth: true },
      children: [
        { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: '账号概览' } },
        { path: 'admin', name: 'admin', component: () => import('../views/AdminHomeView.vue'), meta: { title: '管理中心', requiresAdmin: true } },
        { path: 'forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue'), meta: { title: '访问受限' } },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面不存在' } },
  ],
})

router.beforeEach(async to => {
  if (to.name === 'unavailable') return true
  const auth = useAuthStore()
  await auth.restore()
  if (auth.status === 'failed') return { name: 'unavailable' }
  if (to.meta.requiresAuth && !auth.user) return { name: 'login' }
  if (to.meta.requiresAdmin && !auth.isAdmin) return { name: 'forbidden' }
  if (to.name === 'login' && auth.user) return { name: auth.isAdmin ? 'admin' : 'home' }
  return true
})

router.afterEach(to => { document.title = `${to.meta.title ?? '首页'} · 限量集市` })

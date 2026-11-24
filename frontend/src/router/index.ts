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
    { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { title: '注册' } },
    { path: '/unavailable', name: 'unavailable', component: () => import('../views/UnavailableView.vue'), meta: { title: '连接服务' } },
    {
      path: '/', component: AppLayout,
      children: [
        { path: 'agent', name: 'agent', component: () => import('../views/AgentChatView.vue'), meta: { title: '交易助手', requiresAuth: true } },
        { path: 'admin/categories', name: 'admin-categories', component: () => import('../views/AdminCategoriesView.vue'), meta: { title: '分类管理', requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/products', name: 'admin-products', component: () => import('../views/AdminProductsView.vue'), meta: { title: '商品管理', requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/activities', name: 'admin-activities', component: () => import('../views/AdminActivitiesView.vue'), meta: { title: '活动管理', requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/coupons', name: 'admin-coupons', component: () => import('../views/AdminCouponsView.vue'), meta: { title: '优惠券管理', requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/orders', name: 'admin-orders', component: () => import('../views/AdminOrdersView.vue'), meta: { title: '订单管理', requiresAuth: true, requiresAdmin: true } },
        { path: 'admin/orders/:id', name: 'admin-order-detail', component: () => import('../views/AdminOrderDetailView.vue'), meta: { title: '管理订单详情', requiresAuth: true, requiresAdmin: true } },
        { path: 'orders', name: 'orders', component: () => import('../views/OrdersView.vue'), meta: { title: '我的订单', requiresAuth: true } },
        { path: 'orders/:id', name: 'order-detail', component: () => import('../views/OrderDetailView.vue'), meta: { title: '订单详情', requiresAuth: true } },
        { path: 'cart', name: 'cart', component: () => import('../views/CartView.vue'), meta: { title: '购物车', requiresAuth: true } },
        { path: 'addresses', name: 'addresses', component: () => import('../views/AddressesView.vue'), meta: { title: '收货地址', requiresAuth: true } },
        { path: 'purchase/:productId', name: 'purchase', component: () => import('../views/PurchaseView.vue'), meta: { title: '选购商品', requiresAuth: true } },
        { path: 'checkout/result/:id', name: 'order-created', component: () => import('../views/OrderCreatedView.vue'), meta: { title: '下单结果', requiresAuth: true } },
        { path: 'products', name: 'products', component: () => import('../views/ProductsView.vue'), meta: { title: '商品浏览' } },
        { path: 'activities', name: 'activities', component: () => import('../views/ActivitiesView.vue'), meta: { title: '限量活动' } },
        { path: 'activities/:id', name: 'activity-detail', component: () => import('../views/ActivityDetailView.vue'), meta: { title: '活动详情' } },
        { path: '', name: 'home', component: () => import('../views/HomeView.vue'), meta: { title: '账号概览', requiresAuth: true } },
        { path: 'admin', name: 'admin', component: () => import('../views/AdminHomeView.vue'), meta: { title: '管理中心', requiresAdmin: true, requiresAuth: true } },
        { path: 'forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue'), meta: { title: '访问受限', requiresAuth: true } },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面不存在' } },
  ],
})

router.beforeEach(async to => {
  if (to.name === 'unavailable') return true
  const auth = useAuthStore()
  await auth.restore()
  if (auth.status === 'failed') return { name: 'unavailable', query: { returnTo: to.fullPath } }
  if (to.meta.requiresAuth && !auth.user) return { name: 'login' }
  if (to.meta.requiresAdmin && !auth.isAdmin) return { name: 'forbidden' }
  if ((to.name === 'login' || to.name === 'register') && auth.user) return { name: auth.isAdmin ? 'admin' : 'home' }
  return true
})

router.afterEach(to => { document.title = `${to.meta.title ?? '首页'} · 限量集市` })

import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from './stores/auth'
const router = createRouter({ history: createWebHistory(), scrollBehavior: () => ({ top: 0 }), routes: [
  { path: '/', component: () => import('./views/HomeView.vue'), meta: { title: '宫门' } },
  { path: '/guide', component: () => import('./views/GuideView.vue'), meta: { title: '问衣', requiresAuth: true } },
  { path: '/reception', component: () => import('./views/ReceptionView.vue'), meta: { title: '选购记录', requiresAuth: true } },
  { path: '/tryon', component: () => import('./views/TryOnView.vue'), meta: { title: '试衣镜', requiresAuth: true } },
  { path: '/result/:taskId', component: () => import('./views/ResultView.vue'), meta: { title: '镜中衣', requiresAuth: true } },
  { path: '/culture', component: () => import('./views/CultureView.vue'), meta: { title: '衣冠志' } },
  { path: '/product/:id', component: () => import('./views/ProductView.vue'), meta: { title: '衣裳详情' } },
  { path: '/cart', component: () => import('./views/CartView.vue'), meta: { title: '衣囊', requiresAuth: true } },
  { path: '/payment/:orderId', component: () => import('./views/PaymentView.vue'), meta: { title: '订单支付', requiresAuth: true } },
  { path: '/support', component: () => import('./views/SupportView.vue'), meta: { title: '客服小馆' } },
  { path: '/profile', component: () => import('./views/ProfileView.vue'), meta: { title: '个人中心', requiresAuth: true } },
  { path: '/orders', component: () => import('./views/OrdersView.vue'), meta: { title: '购物订单', requiresAuth: true } },
  { path: '/:pathMatch(.*)*', component: () => import('./views/NotFoundView.vue'), meta: { title: '未寻到此页' } }
] })
router.beforeEach(async (to, from) => {
  if (!to.meta.requiresAuth) return true
  const auth = useAuth()
  try { await auth.refresh() } catch { /* 登录弹窗会展示后续连接错误。 */ }
  if (auth.authenticated) return true
  void auth.requestLogin(to.fullPath, '请先登录，再继续问衣、试穿或查看你的账号资料。')
  return from.matched.length ? false : { path: '/', replace: true }
})
router.afterEach(to => { document.title = `${String(to.meta.title)} · 汉韵镜` })
export default router

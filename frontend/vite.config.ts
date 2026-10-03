import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const proxy = { '/api': { target: env.BACKEND_PROXY_TARGET || 'http://localhost:8082', changeOrigin: true } }
  const isolateAdmin = {
    name: 'storefront-without-admin',
    configureServer(server: import('vite').ViteDevServer) {
      server.middlewares.use((req, res, next) => {
        const path = decodeURIComponent((req.url || '').split('?')[0])
        if (path === '/admin' || path.startsWith('/admin/') || path === '/dist-admin' || path.startsWith('/dist-admin/') || path.includes('/frontend/admin/') || path.includes('/frontend/dist-admin/') || path.startsWith('/api/admin/')) {
          res.statusCode = 404
          res.setHeader('Content-Type', 'text/plain; charset=utf-8')
          res.end('此页面不存在')
          return
        }
        next()
      })
    }
  }
  return { plugins: [vue(), isolateAdmin], server: { host:'127.0.0.1', port:5173, strictPort:true, proxy }, preview: { host:'127.0.0.1', port:5173, strictPort:true, proxy } }
})

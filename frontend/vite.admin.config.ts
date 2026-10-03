import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'
export default defineConfig(({mode})=>{
  const env=loadEnv(mode,process.cwd(),'')
  const proxy={'/api':{target:env.BACKEND_PROXY_TARGET || 'http://localhost:8082',changeOrigin:true}}
  return {root:resolve(process.cwd(),'admin'),define:{'import.meta.env.VITE_ADMIN_APP':JSON.stringify('true')},plugins:[vue()],server:{host:'127.0.0.1',port:5174,strictPort:true,proxy},preview:{host:'127.0.0.1',port:5174,strictPort:true,proxy},build:{outDir:resolve(process.cwd(),'dist-admin'),emptyOutDir:true}}
})

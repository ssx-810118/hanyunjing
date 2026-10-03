import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import AdminApp from './AdminApp.vue'
import AdminView from './AdminView.vue'
import '../src/style.css'
const router=createRouter({history:createWebHistory(),routes:[{path:'/:pathMatch(.*)*',component:AdminView}]})
createApp(AdminApp).use(createPinia()).use(router).mount('#app')

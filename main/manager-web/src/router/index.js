import Vue from 'vue'
import VueRouter from 'vue-router'
import { readSession } from '../security/session'
Vue.use(VueRouter)
const routes = [
{
    path: '/',
    name: 'welcome',
    component: function () {
      return import(/* webpackChunkName: "view-login" */ '../views/login.vue')
    }
  },
{
    path: '/role-config',
    name: 'RoleConfig',
    component: function () {
      return import(/* webpackChunkName: "view-role-config" */ '../views/roleConfig.vue')
    }
  },
{
    path: '/voice-print',
    name: 'VoicePrint',
    component: function () {
      return import(/* webpackChunkName: "view-voice-print" */ '../views/VoicePrint.vue')
    }
  },
{
    path: '/login',
    name: 'login',
    component: function () {
      return import(/* webpackChunkName: "view-login" */ '../views/login.vue')
    }
  },
{
    path: '/home',
    name: 'home',
    component: function () {
      return import(/* webpackChunkName: "view-home" */ '../views/home.vue')
    }
  },
{
    path: '/register',
    name: 'Register',
    component: function () {
      return import(/* webpackChunkName: "view-register" */ '../views/register.vue')
    }
  },
{
    path: '/retrieve-password',
    name: 'RetrievePassword',
    component: function () {
      return import(/* webpackChunkName: "view-retrieve-password" */ '../views/retrievePassword.vue')
    }
  },
{
    path: '/device-center',
    name: 'DeviceCenter',
    component: () => import(/* webpackChunkName: "view-device-center" */ '../views/DeviceCenter.vue')
  },
{
    path: '/device-management',
    name: 'DeviceManagement',
    component: function () {
      return import(/* webpackChunkName: "view-device-management" */ '../views/DeviceManagement.vue')
    }
  },
{
    path: '/organization-management',
    name: 'OrganizationManagement',
    component: function () {
      return import(/* webpackChunkName: "view-organization-management" */ '../views/OrganizationManagement.vue')
    }
  },
{ path: '/model-billing', redirect: to => ({ path: '/allowance', query: to.query }) },
{
    path: '/knowledge-base-management',
    name: 'KnowledgeBaseManagement',
    component: function () {
      return import(/* webpackChunkName: "view-knowledge-base-management" */ '../views/KnowledgeBaseManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '知识库管理'
    }
  },
{
    path: '/knowledge-content-management',
    name: 'KnowledgeContentManagement',
    component: () => import(/* webpackChunkName: "view-knowledge-content-management" */ '../views/KnowledgeContentManagement.vue'),
    meta: { requiresAuth: true, title: '管理知识内容' }
  },
{
    path: '/knowledge-file-upload',
    name: 'KnowledgeFileUpload',
    component: function () {
      return import(/* webpackChunkName: "view-knowledge-file-upload" */ '../views/KnowledgeFileUpload.vue')
    },
    meta: {
      requiresAuth: true,
      title: '文档上传管理'
    }
  },
{
    path: '/asr-hotword-management',
    name: 'AsrHotwordManagement',
    component: () => import(/* webpackChunkName: "view-asr-hotword-management" */ '../views/AsrHotwordManagement.vue'),
    meta: { requiresAuth: true, title: '热词库管理' }
  },
{
    path: '/person-profile-management',
    name: 'PersonProfileManagement',
    component: function () {
      return import(/* webpackChunkName: "view-person-profile-management" */ '../views/PersonProfileManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '用户信息'
    }
  },
{
    path: '/voice-clone-management',
    name: 'VoiceCloneManagement',
    component: function () {
      return import(/* webpackChunkName: "view-voice-clone-management" */ '../views/VoiceCloneManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '音色克隆管理'
    }
  },
{
    path: '/address-book-management',
    name: 'AddressBookManagement',
    component: function () {
      return import(/* webpackChunkName: "view-address-book-management" */ '../views/AddressBookManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '通讯录管理'
    }
  },
{
    path: '/memory-management',
    name: 'MemoryManagement',
    component: function () {
      return import(/* webpackChunkName: "view-memory-management" */ '../views/MemoryManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '记忆管理'
    }
  },
{ path: '/allowance', name: 'Allowance', component: () => import('../views/Allowance.vue') },
{ path: '*', redirect: '/home' }
]
const router = new VueRouter({ base: process.env.VUE_APP_PUBLIC_PATH || '/', routes })
const publicRoutes = ['welcome', 'login', 'Register', 'RetrievePassword']
router.beforeEach((to, from, next) => {
  if (!publicRoutes.includes(to.name) && !readSession('token')) return next({ name: 'login', query: { redirect: to.fullPath } })
  next()
})
export default router

import Vue from 'vue'
import VueRouter from 'vue-router'

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
  // 设备管理页面路由
  {
    path: '/device-management',
    name: 'DeviceManagement',
    component: function () {
      return import(/* webpackChunkName: "view-device-management" */ '../views/DeviceManagement.vue')
    }
  },
  // 添加用户管理路由
  {
    path: '/user-management',
    name: 'UserManagement',
    component: function () {
      return import(/* webpackChunkName: "view-user-management" */ '../views/UserManagement.vue')
    }
  },
  {
    path: '/organization-management',
    name: 'OrganizationManagement',
    component: function () {
      return import(/* webpackChunkName: "view-organization-management" */ '../views/OrganizationManagement.vue')
    }
  },
  {
    path: '/model-config',
    name: 'ModelConfig',
    component: function () {
      return import(/* webpackChunkName: "view-model-config" */ '../views/ModelConfig.vue')
    }
  },
  {
    path: '/params-management',
    name: 'ParamsManagement',
    component: function () {
      return import(/* webpackChunkName: "view-params-management" */ '../views/ParamsManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '参数管理'
    }
  },
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
    path: '/server-side-management',
    name: 'ServerSideManager',
    component: function () {
      return import(/* webpackChunkName: "view-server-side-management" */ '../views/ServerSideManager.vue')
    },
    meta: {
      requiresAuth: true,
      title: '服务端管理'
    }
  },
  {
    path: '/ota-management',
    name: 'OtaManagement',
    component: function () {
      return import(/* webpackChunkName: "view-ota-management" */ '../views/OtaManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: 'OTA管理'
    }
  },
  {
    path: '/voice-resource-management',
    name: 'VoiceResourceManagement',
    component: function () {
      return import(/* webpackChunkName: "view-voice-resource-management" */ '../views/VoiceResourceManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '音色资源开通'
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
    path: '/dict-management',
    name: 'DictManagement',
    component: function () {
      return import(/* webpackChunkName: "view-dict-management" */ '../views/DictManagement.vue')
    }
  },
  {
    path: '/provider-management',
    name: 'ProviderManagement',
    component: function () {
      return import(/* webpackChunkName: "view-provider-management" */ '../views/ProviderManagement.vue')
    }
  },
  {
    path: '/custom-plugin-management',
    redirect: '/function-plugin-management'
  },
  {
    path: '/function-plugin-management',
    name: 'FunctionPluginManagement',
    component: function () {
      return import(/* webpackChunkName: "view-function-plugin-management" */ '../views/FunctionPluginManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '智能体功能库'
    }
  },
  {
    path: '/music-management',
    name: 'MusicManagement',
    component: function () {
      return import(/* webpackChunkName: "view-music-management" */ '../views/MusicManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '音乐管理'
    }
  },
  // 添加默认角色管理路由
  {
    path: '/agent-template-management',
    name: 'AgentTemplateManagement',
    component: function () {
      return import(/* webpackChunkName: "view-agent-template-management" */ '../views/AgentTemplateManagement.vue')
    }
  },
  // 添加模板快速配置路由
  {
    path: '/template-quick-config',
    name: 'TemplateQuickConfig',
    component: function () {
      return import(/* webpackChunkName: "view-template-quick-config" */ '../views/TemplateQuickConfig.vue')
    }
  },
  // 功能配置页面路由
  {
    path: '/feature-management',
    name: 'FeatureManagement',
    component: function () {
      return import(/* webpackChunkName: "view-feature-management" */ '../views/FeatureManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '功能配置'
    }
  },
  // 替换词管理
  {
    path: '/replacement-word-management',
    name: 'ReplacementWordManagement',
    component: function () {
      return import(/* webpackChunkName: "view-replacement-word-management" */ '../views/ReplacementWordManagement.vue')
    },
    meta: {
      requiresAuth: true,
      title: '替换词管理'
    }
  },
  // 通讯录管理页面路由
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
]
const router = new VueRouter({
  base: process.env.VUE_APP_PUBLIC_PATH || '/',
  routes
})

// 全局处理重复导航
const originalPush = VueRouter.prototype.push
VueRouter.prototype.push = function push(location) {
  return originalPush.call(this, location).catch(err => {
    if (err.name === 'NavigationDuplicated') {
      return this.currentRoute
    }
    // 其他错误正常抛出
    throw err
  })
}

// 需要登录才能访问的路由
const protectedRoutes = ['home', 'RoleConfig', 'DeviceManagement', 'UserManagement', 'ModelConfig', 'KnowledgeBaseManagement', 'KnowledgeFileUpload', 'AddressBookManagement', 'FunctionPluginManagement', 'MusicManagement', 'MemoryManagement']

// 路由守卫
router.beforeEach((to, from, next) => {
  // 检查是否是需要保护的路由
  if (protectedRoutes.includes(to.name)) {
    // 从localStorage获取token
    const token = localStorage.getItem('token')
    if (!token) {
      // 未登录，跳转到登录页
      next({ name: 'login', query: { redirect: to.fullPath } })
      return
    }
  }
  next()
})

export default router

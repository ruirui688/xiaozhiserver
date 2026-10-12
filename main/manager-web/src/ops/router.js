import Vue from 'vue';
import VueRouter from 'vue-router';
import store from '@/store';
import { operations } from '@/apis/platform';
import { readSession } from '@/security/session';
import { canVisitOperations } from './navigation';
Vue.use(VueRouter);
const router = new VueRouter({ routes: [
  { path: '/login', component: () => import('./OpsLogin.vue') },
  { path: '/enroll', component: () => import('./OpsEnrollment.vue') },
  { path: '/recover', component: () => import('./OpsEnrollment.vue') },
  { path: '/', redirect: '/home' },
  { path: '/home', component: () => import('./OpsHome.vue') },
  { path: '/services', component: () => import('./OpsServices.vue') },
  { path: '/model-billing', component: () => import('./OpsSupplierCosts.vue') },
  { path: '/model-config', component: () => import('../views/ModelConfig.vue') },
  { path: '/official-resources', component: () => import('./OpsOfficialResources.vue') },
  { path: '/ota-management', component: () => import('./OpsFirmware.vue') },
  { path: '/function-plugin-management', component: () => import('../views/FunctionPluginManagement.vue') },
  { path: '/params-management', component: () => import('../views/ParamsManagement.vue') },
  { path: '/server-side-management', component: () => import('../views/ServerSideManager.vue') },
  { path: '/approvals', component: () => import('./OpsApprovals.vue') },
  { path: '/audit', component: () => import('./OpsAudit.vue') },
  { path: '/iam', component: () => import('./OpsIdentity.vue') },
  { path: '*', redirect: '/home' },
] });
router.beforeEach(async (to, from, next) => {
  if (['/login','/enroll','/recover'].includes(to.path)) return next();
  if (!readSession('token')) return next('/login');
  try {
    const profile = await operations.me();
    if (profile?.audience !== 'OPS') throw new Error('员工身份不匹配');
    store.commit('setUserInfo', profile);
    if (!canVisitOperations(profile, to.path)) return next({ path: '/home', query: { denied: '1' } });
    next();
  } catch (_) { store.commit('clearAuth'); next('/login'); }
});
export default router;

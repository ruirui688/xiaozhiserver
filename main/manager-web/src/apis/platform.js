import RequestService from './httpRequest';
import { getServiceUrl } from './api';
import { platformErrorMessage } from '@/utils/allowance';

export function platformRequest(method, path, data = {}, options = {}) {
  return new Promise((resolve, reject) => {
    const receive = response => {
      if (response?.data?.code === 0) resolve(response.data.data);
      else reject(new Error(platformErrorMessage(response?.data?.msg)));
    };
    const request = RequestService.sendRequest().url(`${getServiceUrl()}${path}`).method(method).data(data);
    if (options.multipart) request.header({});
    if (options.headers) request.header({ 'Content-Type': 'application/json; charset=utf-8', ...options.headers });
    request.success(receive).fail(receive).networkFail(response => reject(new Error(response?.status === 403 ? '当前岗位没有此操作权限' : '服务暂时不可用，请重试'))).send();
  });
}
export const userPlatform = {
  account: () => platformRequest('GET', '/user/platform/account'),
  summary: workspaceId => platformRequest('GET', '/user/platform/summary', { workspaceId }),
  ledger: params => platformRequest('GET', '/user/platform/ledger', params),
  workspaceLimits: workspaceId => platformRequest('GET', '/user/platform/workspace-limits', { workspaceId }),
  saveWorkspaceLimits: data => platformRequest('PUT', '/user/platform/workspace-limits', data),
  trial: () => platformRequest('GET', '/user/platform/trial'),
  allowanceRequests: params => platformRequest('GET', '/user/platform/allowance-requests', params),
  requestAllowance: data => platformRequest('POST', '/user/platform/allowance-requests', data),
};
export const operations = {
  identityPolicy: () => platformRequest('GET', '/ops/auth/identity-policy'),
  login: data => platformRequest('POST', '/ops/auth/login', data),
  me: () => platformRequest('GET', '/ops/auth/me'),
  logout: () => platformRequest('POST', '/ops/auth/logout'),
  services: () => platformRequest('GET', '/ops/platform/services'),
  addService: data => platformRequest('POST', '/ops/platform/services', data),
  grantService: data => platformRequest('POST', '/ops/platform/services/grant', data),
  pool: () => platformRequest('GET', '/ops/platform/pool'),
  adjustPool: data => platformRequest('POST', '/ops/platform/pool', data),
  accounts: params => platformRequest('GET', '/ops/platform/accounts', params),
  grant: data => platformRequest('POST', '/ops/platform/allowance/grant', data),
  freeze: data => platformRequest('POST', '/ops/platform/allowance/freeze', data),
  credentials: params => platformRequest('GET', '/ops/platform/credentials', params),
  addCredential: data => platformRequest('POST', '/ops/platform/credentials', data),
  assignCredential: data => platformRequest('POST', '/ops/platform/credentials/assign', data),
  rotateCredential: data => platformRequest('POST', '/ops/platform/credentials/rotate', data),
  revokeCredential: (id, reason) => platformRequest('POST', `/ops/platform/credentials/${encodeURIComponent(id)}/revoke`, { reason }),
  credentialInventory: () => platformRequest('GET', '/ops/platform/credentials/inventory'),
  credentialPending: params => platformRequest('GET', '/ops/platform/credentials/pending', params),
  trial: () => platformRequest('GET', '/ops/platform/trial'),
  allowanceRequests: params => platformRequest('GET', '/ops/platform/allowance/requests', params),
  approveAllowance: (id, data) => platformRequest('POST', `/ops/platform/allowance/requests/${encodeURIComponent(id)}/approve`, data),
  rejectAllowance: (id, reason) => platformRequest('POST', `/ops/platform/allowance/requests/${encodeURIComponent(id)}/reject`, { reason }),
  rates: params => platformRequest('GET', '/ops/platform/rates', params),
  addRate: data => platformRequest('POST', '/ops/platform/rates', data),
};

import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(method, path, data, { multipart = false, binary = false } = {}) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('请求超时，请刷新后确认结果')), 65000);
    const fail = message => { clearTimeout(timer); reject(new Error(message)); };
    const receive = response => {
      clearTimeout(timer);
      const result = response && response.data;
      if (binary && result instanceof Blob && /text\/csv/i.test(result.type)) { resolve(result); return; }
      if (result && result.code === 0) resolve(result.data);
      else fail((result && result.msg) || '计费请求失败，请重试');
    };
    try {
      const pending = RequestService.sendRequest().url(`${getServiceUrl()}/billing${path}`).method(method).data(data || {});
      if (multipart) pending.header({});
      if (binary) pending.type('blob');
      pending.timeout(60000).success(receive).fail(receive)
        .networkFail(response => {
          const status = response && response.status;
          fail(status === 401 ? '登录已过期，请重新登录' : status === 403 ? '没有权限访问计费数据' : `计费服务暂时不可用${status ? `（HTTP ${status}）` : ''}`);
        }).send();
    } catch (_) { fail('计费请求未能发送，请重新登录后重试'); }
  });
}

export default {
  overview: filters => request('GET', '/overview', filters),
  groups: filters => request('GET', '/groups', filters),
  events: filters => request('GET', '/events', filters),
  bills: filters => request('GET', '/bills', filters),
  options: () => request('GET', '/options'),
  benefits: filters => request('GET', '/benefits', filters),
  syncBenefits: () => request('POST', '/benefits/sync', {}),
  bindings: filters => request('GET', '/bindings', filters),
  createBinding: data => request('POST', '/bindings', data),
  deleteBinding: id => request('DELETE', `/bindings/${encodeURIComponent(id)}`),
  sync: data => request('POST', '/sync', data),
  importBill(file, provider, accountRef) {
    const form = new FormData();
    form.append('file', file); form.append('provider', provider); form.append('accountRef', accountRef);
    return request('POST', '/import', form, { multipart: true });
  },
  importTemplate: provider => request('GET', '/import-template', { provider }, { binary: true }),
};

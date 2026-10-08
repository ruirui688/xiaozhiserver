import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(method, path = '', data) {
  return new Promise((resolve, reject) => {
    const builder = RequestService.sendRequest().url(`${getServiceUrl()}/resource/asr-hotwords${path}`).method(method).timeout(90000);
    if (data !== undefined) builder.data(data);
    builder.success(({ data: response }) => {
      RequestService.clearRequestTime();
      if (response && response.code === 0) resolve(response.data);
      else reject(new Error(response && response.msg || '热词操作失败'));
    }).fail(response => reject(new Error(response && response.data && response.data.msg || '热词操作失败，请刷新后重试')))
      .networkFail(() => reject(new Error('网络请求失败，请刷新确认操作结果'))).send();
  });
}
const path = id => `/${encodeURIComponent(id)}`;
export default {
  list: () => request('GET'),
  models: () => request('GET', '/models'),
  agents: () => request('GET', '/agents'),
  remote: modelId => request('GET', `/remote?modelId=${encodeURIComponent(modelId)}`),
  importRemote: payload => request('POST', '/import', payload),
  create: payload => request('POST', '', payload),
  update: (id, payload) => request('PUT', path(id), payload),
  remove: id => request('DELETE', path(id)),
  publish: id => request('POST', `${path(id)}/publish`),
  unapply: (id, agentIds) => request('POST', `${path(id)}/unapply`, { agentIds }),
  apply: (id, agentIds) => request('POST', `${path(id)}/apply`, { agentIds }),
};

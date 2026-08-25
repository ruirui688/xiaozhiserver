import { getServiceUrl } from '../api'
import RequestService from '../httpRequest'

function request(method, path, data, callback, fail) {
  const req = RequestService.sendRequest()
    .url(`${getServiceUrl()}${path}`)
    .method(method)
    .success(res => {
      RequestService.clearRequestTime()
      if (callback) callback(res)
    })
    .networkFail(fail || (() => {}))
    .fail(fail || (() => {}))
  if (data) req.data(data)
  req.send()
}

function base(type, id) {
  return `/maintenance/${String(type).toLowerCase()}/${encodeURIComponent(id)}`
}

export default {
  policy(type, id, callback, fail) { request('GET', `${base(type, id)}/policy`, null, callback, fail) },
  savePolicy(type, id, data, callback, fail) { request('PUT', `${base(type, id)}/policy`, data, callback, fail) },
  run(type, id, callback, fail) { request('POST', `${base(type, id)}/runs`, {}, callback, fail) },
  runs(type, id, callback, fail) { request('GET', `${base(type, id)}/runs`, null, callback, fail) },
  proposals(type, id, callback, fail) { request('GET', `${base(type, id)}/proposals`, null, callback, fail) },
  approve(type, id, proposalId, callback, fail) {
    request('POST', `${base(type, id)}/proposals/${proposalId}/approve`, {}, callback, fail)
  },
  reject(type, id, proposalId, callback, fail) {
    request('POST', `${base(type, id)}/proposals/${proposalId}/reject`, {}, callback, fail)
  }
}

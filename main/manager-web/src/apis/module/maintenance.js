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
  run(type, id, data, callback, fail) { request('POST', `${base(type, id)}/runs`, data || {}, callback, fail) },
  runs(type, id, callback, fail) { request('GET', `${base(type, id)}/runs`, null, callback, fail) },
  runEvents(type, id, runId, afterId, callback, fail) {
    request('GET', `${base(type, id)}/runs/${runId}/events?after_id=${afterId || 0}`, null, callback, fail)
  },
  proposals(type, id, callback, fail) { request('GET', `${base(type, id)}/proposals`, null, callback, fail) },
  conversations(type, id, includeArchived, callback, fail) {
    request('GET', `${base(type, id)}/conversations?include_archived=${includeArchived ? 'true' : 'false'}`, null, callback, fail)
  },
  createConversation(type, id, data, callback, fail) {
    request('POST', `${base(type, id)}/conversations`, data, callback, fail)
  },
  updateConversation(type, id, conversationId, data, callback, fail) {
    request('PUT', `${base(type, id)}/conversations/${conversationId}`, data, callback, fail)
  },
  deleteConversation(type, id, conversationId, callback, fail) {
    request('DELETE', `${base(type, id)}/conversations/${conversationId}`, null, callback, fail)
  },
  conversationMessages(type, id, conversationId, callback, fail) {
    request('GET', `${base(type, id)}/conversations/${conversationId}/messages`, null, callback, fail)
  },
  conversationRuns(type, id, conversationId, callback, fail) {
    request('GET', `${base(type, id)}/conversations/${conversationId}/runs`, null, callback, fail)
  },
  conversationEvents(type, id, conversationId, afterId, callback, fail) {
    request('GET', `${base(type, id)}/conversations/${conversationId}/events?after_id=${afterId || 0}`, null, callback, fail)
  },
  conversationProposals(type, id, conversationId, callback, fail) {
    request('GET', `${base(type, id)}/conversations/${conversationId}/proposals`, null, callback, fail)
  },
  runConversation(type, id, conversationId, data, callback, fail) {
    request('POST', `${base(type, id)}/conversations/${conversationId}/runs`, data, callback, fail)
  },
  approve(type, id, proposalId, callback, fail) {
    request('POST', `${base(type, id)}/proposals/${proposalId}/approve`, {}, callback, fail)
  },
  reject(type, id, proposalId, callback, fail) {
    request('POST', `${base(type, id)}/proposals/${proposalId}/reject`, {}, callback, fail)
  }
}

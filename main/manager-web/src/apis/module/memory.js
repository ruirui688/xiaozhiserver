import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(method, url, data, callback, networkFail) {
  const req = RequestService.sendRequest()
    .url(`${getServiceUrl()}${url}`)
    .method(method)
    .success((res) => {
      RequestService.clearRequestTime();
      if (callback) callback(res);
    })
    .networkFail((err) => {
      if (networkFail) {
        networkFail(err);
        return;
      }
      RequestService.reAjaxFun(() => request(method, url, data, callback, networkFail));
    });
  if (data) {
    req.data(data);
  }
  req.send();
}

export default {
  dashboard(agentId, callback, networkFail) {
    request('GET', `/agent/${agentId}/memories`, null, callback, networkFail);
  },
  createFact(agentId, data, callback) {
    request('POST', `/agent/${agentId}/memories/facts`, data, callback);
  },
  updateFact(agentId, memoryId, data, callback) {
    request('PUT', `/agent/${agentId}/memories/facts/${memoryId}`, data, callback);
  },
  archiveFact(agentId, memoryId, callback) {
    request('POST', `/agent/${agentId}/memories/facts/${memoryId}/archive`, {}, callback);
  },
  deleteFact(agentId, memoryId, callback) {
    request('DELETE', `/agent/${agentId}/memories/facts/${memoryId}`, null, callback);
  },
  remember(agentId, data, callback) {
    request('POST', `/agent/${agentId}/memories/remember`, data, callback);
  },
  forget(agentId, data, callback) {
    request('POST', `/agent/${agentId}/memories/forget`, data, callback);
  },
  confirmCandidate(agentId, candidateId, callback) {
    request('POST', `/agent/${agentId}/memories/candidates/${candidateId}/confirm`, {}, callback);
  },
  rejectCandidate(agentId, candidateId, callback) {
    request('POST', `/agent/${agentId}/memories/candidates/${candidateId}/reject`, {}, callback);
  }
};

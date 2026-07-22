import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(method, url, data, callback, networkFail, fail, timeout) {
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
      RequestService.reAjaxFun(() => request(method, url, data, callback, networkFail, fail, timeout));
    });
  if (fail) {
    req.fail(fail);
  }
  if (timeout) {
    req.timeout(timeout);
  }
  if (data) {
    req.data(data);
  }
  req.send();
}

export default {
  dashboard(agentId, callback, networkFail, fail) {
    request('GET', `/agent/${agentId}/memories`, null, callback, networkFail, fail);
  },
  createFact(agentId, data, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/facts`, data, callback, networkFail, fail);
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
  clear(agentId, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/clear`, {}, callback, networkFail, fail);
  },
  rebuildPreview(agentId, data, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/rebuild-preview`, data, callback, networkFail, fail, 300000);
  },
  rebuild(agentId, data, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/rebuild`, data, callback, networkFail, fail, 30000);
  },
  latestRebuildTask(agentId, callback, networkFail, fail) {
    request('GET', `/agent/${agentId}/memories/rebuild-task`, null, callback, networkFail, fail);
  },
  rebuildTask(agentId, taskId, callback, networkFail, fail) {
    request('GET', `/agent/${agentId}/memories/rebuild-task/${taskId}`, null, callback, networkFail, fail);
  },
  reviewPreview(agentId, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/review-preview`, {}, callback, networkFail, fail, 300000);
  },
  review(agentId, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/review`, {}, callback, networkFail, fail, 300000);
  },
  remember(agentId, data, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/remember`, data, callback, networkFail, fail, 300000);
  },
  forget(agentId, data, callback, networkFail, fail) {
    request('POST', `/agent/${agentId}/memories/forget`, data, callback, networkFail, fail, 300000);
  },
  confirmCandidate(agentId, candidateId, callback) {
    request('POST', `/agent/${agentId}/memories/candidates/${candidateId}/confirm`, {}, callback);
  },
  rejectCandidate(agentId, candidateId, callback) {
    request('POST', `/agent/${agentId}/memories/candidates/${candidateId}/reject`, {}, callback);
  }
};

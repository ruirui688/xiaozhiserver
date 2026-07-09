import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function networkErrorResponse(message) {
  return { data: { code: -1, msg: message, data: null } };
}

function send(path, method, data, callback, timeout = 10000) {
  const request = RequestService.sendRequest()
    .url(`${getServiceUrl()}${path}`)
    .method(method)
    .timeout(timeout)
    .success((res) => {
      RequestService.clearRequestTime();
      callback(res);
    })
    .fail((res) => callback(res))
    .networkFail(() => {
      if (callback) callback(networkErrorResponse('Otto舞蹈调试请求失败'));
    });
  if (data !== undefined) request.data(data);
  request.send();
}

export default {
  connect(deviceId, host, callback) {
    send(`/device/otto-debug/${deviceId}/connect`, 'POST', { host }, callback, 8000);
  },
  snapshot(deviceId, callback) {
    send(`/device/otto-debug/${deviceId}/snapshot`, 'POST', {}, callback, 8000);
  },
  listTools(deviceId, params, callback) {
    send(`/device/otto-debug/${deviceId}/tools`, 'POST', params || {}, callback, 8000);
  },
  callTool(deviceId, params, callback) {
    send(`/device/otto-debug/${deviceId}/tool-call`, 'POST', params, callback, 12000);
  },
  callToolFast(deviceId, params, callback) {
    send(`/device/otto-debug/${deviceId}/tool-call-fast`, 'POST', params, callback, 5000);
  },
  getConfig(deviceId, callback) {
    send(`/device/otto-debug/${deviceId}/config`, 'GET', undefined, callback, 8000);
  },
  setConfig(deviceId, params, callback) {
    send(`/device/otto-debug/${deviceId}/config`, 'POST', params, callback, 8000);
  }
};

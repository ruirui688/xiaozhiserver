import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(method, path, data, callback, failCallback, options = {}) {
  const builder = RequestService.sendRequest()
    .url(`${getServiceUrl()}${path}`)
    .method(method);
  if (data !== undefined && data !== null) {
    builder.data(data);
  }
  if (options.headers) {
    builder.header(options.headers);
  }
  if (options.responseType) {
    builder.type(options.responseType);
  }
  builder
    .success((res) => {
      RequestService.clearRequestTime();
      callback && callback(res);
    })
    .fail((err) => failCallback && failCallback(err))
    .networkFail((err) => failCallback && failCallback(err))
    .send();
}

export default {
  list(keyword, callback, failCallback) {
    const query = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
    request('GET', `/resource/persons${query}`, null, callback, failCallback);
  },

  devices(callback, failCallback) {
    request('GET', '/resource/persons/devices', null, callback, failCallback);
  },

  create(payload, callback, failCallback) {
    request('POST', '/resource/persons', payload, callback, failCallback);
  },

  update(id, payload, callback, failCallback) {
    request('PUT', `/resource/persons/${encodeURIComponent(id)}`, payload, callback, failCallback);
  },

  remove(id, callback, failCallback) {
    request('DELETE', `/resource/persons/${encodeURIComponent(id)}`, null, callback, failCallback);
  },

  uploadFace(id, file, callback, failCallback) {
    const formData = new FormData();
    formData.append('file', file);
    request(
      'POST',
      `/resource/persons/${encodeURIComponent(id)}/face`,
      formData,
      callback,
      failCallback,
      { headers: {} }
    );
  },

  loadFace(id, callback, failCallback) {
    request(
      'GET',
      `/resource/persons/${encodeURIComponent(id)}/face?v=${Date.now()}`,
      null,
      callback,
      failCallback,
      { responseType: 'arraybuffer' }
    );
  },

  capability(callback, failCallback) {
    request('GET', '/resource/person-recognition/capability', null, callback, failCallback);
  }
};

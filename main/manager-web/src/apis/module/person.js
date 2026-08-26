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

  agents(callback, failCallback) {
    request('GET', '/resource/persons/agents', null, callback, failCallback);
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

  publish(id, callback, failCallback) {
    request('POST', `/resource/persons/${encodeURIComponent(id)}/publish`, null, callback, failCallback);
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
  },

  receptionProfile(personId, callback, failCallback) {
    request('GET', `/resource/reception/persons/${encodeURIComponent(personId)}`, null, callback, failCallback);
  },

  saveReceptionProfile(personId, payload, callback, failCallback) {
    request('PUT', `/resource/reception/persons/${encodeURIComponent(personId)}`, payload, callback, failCallback);
  },

  appointments(personId, callback, failCallback) {
    request('GET', `/resource/reception/persons/${encodeURIComponent(personId)}/appointments`, null, callback, failCallback);
  },

  createAppointment(personId, payload, callback, failCallback) {
    request('POST', `/resource/reception/persons/${encodeURIComponent(personId)}/appointments`, payload, callback, failCallback);
  },

  updateAppointment(appointmentId, payload, callback, failCallback) {
    request('PUT', `/resource/reception/appointments/${encodeURIComponent(appointmentId)}`, payload, callback, failCallback);
  },

  removeAppointment(appointmentId, callback, failCallback) {
    request('DELETE', `/resource/reception/appointments/${encodeURIComponent(appointmentId)}`, null, callback, failCallback);
  },

  receptionWorkspaceConfig(callback, failCallback) {
    request('GET', '/resource/reception/workspace-config', null, callback, failCallback);
  },

  saveReceptionWorkspaceConfig(payload, callback, failCallback) {
    request('PUT', '/resource/reception/workspace-config', payload, callback, failCallback);
  }
};

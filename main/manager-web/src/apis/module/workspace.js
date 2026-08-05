import { getServiceUrl } from '../api'
import RequestService from '../httpRequest'

export default {
  list(callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/list`)
      .method('GET')
      .success(callback)
      .send()
  },
  current(callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/current`)
      .method('GET')
      .success(callback)
      .send()
  },
  quota(callback, failCallback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/current/quota`)
      .method('GET')
      .success(callback)
      .fail(failCallback)
      .networkFail(failCallback)
      .send()
  },
  create(data, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace`)
      .method('POST')
      .data(data)
      .success(callback)
      .send()
  },
  update(workspaceId, data, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}`)
      .method('PUT')
      .data(data)
      .success(callback)
      .send()
  },
  members(workspaceId, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/members`)
      .method('GET')
      .success(callback)
      .send()
  },
  invitations(workspaceId, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/invitations`)
      .method('GET')
      .success(callback)
      .send()
  },
  invite(workspaceId, data, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/invitations`)
      .method('POST')
      .data(data)
      .success(callback)
      .send()
  },
  accept(code, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/invitations/${code}/accept`)
      .method('POST')
      .success(callback)
      .send()
  },
  revokeInvitation(workspaceId, invitationId, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/invitations/${invitationId}`)
      .method('DELETE')
      .success(callback)
      .send()
  },
  removeMember(workspaceId, userId, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/members/${userId}`)
      .method('DELETE')
      .success(callback)
      .send()
  },
  leave(workspaceId, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/members/me`)
      .method('DELETE')
      .success(callback)
      .send()
  },
  updateRole(workspaceId, userId, role, callback) {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/${workspaceId}/members/${userId}/role`)
      .method('PUT')
      .data({ role })
      .success(callback)
      .send()
  }
}

import { http } from '@/http/request/alova'

export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface Workspace {
  id: string
  name: string
  currentUserRole: WorkspaceRole
  canWrite: boolean
}

export interface WorkspaceMember {
  userId: number
  username: string
  role: WorkspaceRole
  joinedAt?: string
}

export interface WorkspaceInvitation {
  code: string
  workspaceId: string
  username: string
  role: WorkspaceRole
  expiresAt?: string
}

export function getWorkspaceList() {
  return http.Get<Workspace[]>('/workspace/list', {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
    cacheFor: {
      expire: 0,
    },
  })
}

export function getWorkspaceMembers(workspaceId: string) {
  return http.Get<WorkspaceMember[]>(`/workspace/${workspaceId}/members`, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
    cacheFor: {
      expire: 0,
    },
  })
}

export function inviteWorkspaceMember(workspaceId: string, data: { username: string, role: WorkspaceRole }) {
  return http.Post<WorkspaceInvitation>(`/workspace/${workspaceId}/invitations`, data, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
  })
}

export function acceptWorkspaceInvitation(code: string) {
  return http.Post<Workspace>(`/workspace/invitations/${code}/accept`, {}, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
  })
}

export function updateWorkspaceMemberRole(workspaceId: string, userId: number, role: WorkspaceRole) {
  return http.Put<void>(`/workspace/${workspaceId}/members/${userId}/role`, { role }, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
  })
}

export function removeWorkspaceMember(workspaceId: string, userId: number) {
  return http.Delete<void>(`/workspace/${workspaceId}/members/${userId}`, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
  })
}

export function leaveWorkspace(workspaceId: string) {
  return http.Delete<void>(`/workspace/${workspaceId}/members/me`, {
    meta: {
      ignoreAuth: false,
      toast: false,
    },
  })
}

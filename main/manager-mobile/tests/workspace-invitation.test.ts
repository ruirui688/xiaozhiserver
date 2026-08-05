import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const api = fs.readFileSync(path.join(root, 'src/api/workspace.ts'), 'utf8')
const page = fs.readFileSync(path.join(root, 'src/pages/organization/index.vue'), 'utf8')

test('organization quota exposes and revokes pending invitations', () => {
  assert.match(api, /getWorkspaceInvitations/)
  assert.match(api, /revokeWorkspaceInvitation/)
  assert.match(page, /const occupiedSlots = computed/)
  assert.match(page, /待接受邀请/)
  assert.match(page, /confirmRevokeInvitation/)
  assert.match(page, /occupiedSlots >= maxMembers/)
})

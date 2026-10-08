import Api, { getServiceUrl } from '@/apis/api';
import RequestService from '@/apis/httpRequest';

export function deviceRequest(invoke) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('请求超时，请刷新确认设备状态')), 35000);
    try {
      invoke(({ data } = {}) => {
        clearTimeout(timer);
        if (data && data.code === 0) resolve(data.data);
        else reject(new Error((data && data.msg) || '设备请求失败'));
      });
    } catch (error) { clearTimeout(timer); reject(error); }
  });
}

export function listWorkspaceAgents() {
  return deviceRequest(callback => RequestService.sendRequest()
    .url(`${getServiceUrl()}/agent/list`).method('GET')
    .success(callback).fail(callback)
    .networkFail(() => callback({ data: { code: -1, msg: '加载智能体失败' } })).send());
}

export async function listWorkspaceDevices(agents) {
  // Limit fan-out for workspaces containing many agents. Failed reads must not look like an empty list.
  const devices = new Map();
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(4, agents.length) }, async () => {
    while (cursor < agents.length) {
      const agent = agents[cursor++];
      const rows = await deviceRequest(done => Api.device.getAgentBindDevices(agent.id, done));
      for (const row of rows || []) devices.set(row.id, { ...row, agentId: agent.id, agentName: agent.agentName });
    }
  }));
  return Array.from(devices.values());
}

export async function switchDeviceAgent(deviceId, agent) {
  let scenes;
  try {
    scenes = await deviceRequest(done => Api.device.getDeviceScenes(deviceId, done));
  } catch (error) {
    throw new Error(`${error.message}；尚未提交切换，可重试`);
  }
  let binding = scenes.find(scene => scene.agentId === agent.id);
  if (binding && binding.enabled === 0) throw new Error('该智能体的场景已禁用，请先在场景管理中启用');
  if (binding && binding.current) return binding;
  if (!binding) {
    try {
      binding = await deviceRequest(done => Api.device.createDeviceScene(deviceId, {
        agentId: agent.id, sceneName: (agent.agentName || '智能体场景').slice(0, 64),
        enabled: 1, switchAliases: [], sort: scenes.length
      }, done));
    } catch (error) {
      throw new Error(`${error.message}；尚未提交切换，请刷新场景列表确认关联状态`);
    }
  }
  // Keep the binding on failure: it can be retried or managed in the existing scene dialog.
  try {
    return await deviceRequest(done => Api.device.switchDeviceScene(deviceId, binding.id, done));
  } catch (error) {
    throw new Error(`${error.message}；请刷新确认切换结果，关联场景已保留`);
  }
}

export function sceneStatusText(status) {
  return ({ REQUESTED: '已提交，等待设备确认', SCHEDULED: '等待本轮结束',
    APPLIED: '已生效', FAILED: '切换失败', ROLLED_BACK: '切换失败，已回滚' })[status] || '尚无切换记录';
}

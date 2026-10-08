import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function networkErrorResponse(message) {
    return {
        data: {
            code: -1,
            msg: message,
            data: []
        }
    };
}

function networkErrorSummary(err) {
    if (!err) return 'unknown';
    if (err.status) return `status=${err.status}`;
    if (err.message) return err.message;
    return 'network error';
}

function sceneErrorResponse(message, error) {
    const status = Number(error && error.status);
    const hints = {
        401: '登录已失效，请重新登录',
        403: '请求被拒绝，请检查工作空间权限或网关限制',
        404: '接口不存在，请检查管理 API 版本及代理路由',
        405: '接口不支持此操作，请检查管理 API 版本及代理路由',
        429: '请求过于频繁，请稍后重试',
        500: '管理 API 内部错误，请检查服务日志',
        502: '网关无法连接管理 API，请检查服务状态',
        503: '管理 API 暂时不可用，请稍后重试',
        504: '网关等待管理 API 超时，请检查服务状态'
    };
    // Never echo proxy HTML, request headers or raw exception bodies into the UI.
    const detail = status >= 100 && status <= 599
        ? `HTTP ${status}：${hints[status] || '服务器请求失败，请检查服务日志'}`
        : status === 1 ? '请求超时，请检查网络和服务状态'
            : '未收到有效响应，请检查网络连接和服务状态';
    return networkErrorResponse(`${message}（${detail}）`);
}

export default {
    // 已绑设备
    getAgentBindDevices(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/bind/${agentId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail(callback)
            .networkFail((err) => {
                console.error('获取设备列表失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取设备列表失败'));
            }).send();
    },
    // 获取设备真实音频预览短期票据
    createAudioPreviewTicket(deviceId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/${deviceId}/audio-preview-ticket`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取真实音频预览票据失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取真实音频预览票据失败'));
            }).send();
    },
    // 解绑设备
    unbindDevice(device_id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/unbind`)
            .method('POST')
            .data({ deviceId: device_id })
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail(callback)
            .networkFail((err) => {
                console.error('解绑设备失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('解绑设备失败'));
            }).send();
    },
    // 绑定设备
    bindDevice(agentId, deviceCode, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/bind/${agentId}/${deviceCode}`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('绑定设备失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('绑定设备失败'));
            }).send();
    },
    updateDeviceInfo(id, payload, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/update/${id}`)
            .method('PUT')
            .data(payload)
            .success((res) => {
                RequestService.clearRequestTime()
                callback(res)
            })
            .fail(callback)
            .networkFail((err) => {
                console.error('更新设备信息失败:', networkErrorSummary(err))
                if (callback) callback(networkErrorResponse('更新设备信息失败'))
            }).send()
    },
    // 手动添加设备
    manualAddDevice(params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/manual-add`)
            .method('POST')
            .data(params)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('手动添加设备失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('手动添加设备失败'));
            }).send();
    },
    // 获取设备状态
    getDeviceStatus(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/bind/${agentId}`)
            .method('POST')
            .data({}) // 发送空对象作为请求体
            .timeout(12000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取设备状态失败:', networkErrorSummary(err));
                callback(networkErrorResponse('获取设备状态失败'));
            }).send();
    },
    getDeviceScenes(deviceId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes`)
            .method('GET')
            .success(callback).fail(callback)
            .networkFail(err => callback(sceneErrorResponse('获取设备场景失败', err)))
            .send();
    },
    createDeviceScene(deviceId, payload, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes`)
            .method('POST').data(payload).success(callback).fail(callback)
            .networkFail(err => callback(sceneErrorResponse('创建设备场景失败', err)))
            .send();
    },
    updateDeviceScene(deviceId, bindingId, payload, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes/${bindingId}`)
            .method('PUT').data(payload).success(callback).fail(callback)
            .networkFail(err => callback(sceneErrorResponse('更新设备场景失败', err)))
            .send();
    },
    deleteDeviceScene(deviceId, bindingId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes/${bindingId}`)
            .method('DELETE').success(callback).fail(callback)
            .networkFail(err => callback(sceneErrorResponse('删除设备场景失败', err)))
            .send();
    },
    switchDeviceScene(deviceId, bindingId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/active-scene`)
            .method('PUT')
            .data({ bindingId, switchRequestId: `web-${Date.now()}-${Math.random().toString(16).slice(2)}` })
            .success(callback).fail(callback)
            .networkFail(err => callback(sceneErrorResponse('切换设备场景失败', err)))
            .send();
    },
}

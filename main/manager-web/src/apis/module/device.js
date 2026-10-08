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
            .networkFail(() => callback(networkErrorResponse('获取设备场景失败')))
            .send();
    },
    createDeviceScene(deviceId, payload, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes`)
            .method('POST').data(payload).success(callback).fail(callback)
            .networkFail(() => callback(networkErrorResponse('创建设备场景失败')))
            .send();
    },
    updateDeviceScene(deviceId, bindingId, payload, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes/${bindingId}`)
            .method('PUT').data(payload).success(callback).fail(callback)
            .networkFail(() => callback(networkErrorResponse('更新设备场景失败')))
            .send();
    },
    deleteDeviceScene(deviceId, bindingId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/scenes/${bindingId}`)
            .method('DELETE').success(callback).fail(callback)
            .networkFail(() => callback(networkErrorResponse('删除设备场景失败')))
            .send();
    },
    switchDeviceScene(deviceId, bindingId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/devices/${encodeURIComponent(deviceId)}/active-scene`)
            .method('PUT')
            .data({ bindingId, switchRequestId: `web-${Date.now()}-${Math.random().toString(16).slice(2)}` })
            .success(callback).fail(callback)
            .networkFail(() => callback(networkErrorResponse('切换设备场景失败')))
            .send();
    },
}

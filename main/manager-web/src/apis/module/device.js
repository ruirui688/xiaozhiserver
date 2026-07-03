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
            .networkFail((err) => {
                console.error('获取设备列表失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取设备列表失败'));
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
            .networkFail((err) => {
                console.error('更新OTA状态失败:', networkErrorSummary(err))
                if (callback) callback(networkErrorResponse('更新OTA状态失败'))
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
    // 获取设备对话调试连接信息
    getDeviceDebugInfo(deviceId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/debug/${deviceId}`)
            .method('GET')
            .timeout(10000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取设备调试连接信息失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取设备调试连接信息失败'));
            }).send();
    },
    // 获取设备对话调试状态
    getDeviceDebugStatus(deviceId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/debug/status/${deviceId}`)
            .method('GET')
            .timeout(10000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取设备调试状态失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取设备调试状态失败'));
            }).send();
    },
    // 获取设备MCP工具列表
    getDeviceTools(deviceId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/tools/list/${deviceId}`)
            .method('POST')
            .data({})
            .timeout(10000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取设备工具列表失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('获取设备工具列表失败'));
            }).send();
    },
    // 调用设备MCP工具
    callDeviceTool(deviceId, params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/device/tools/call/${deviceId}`)
            .method('POST')
            .data(params)
            .timeout(20000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                callback(res);
            })
            .networkFail((err) => {
                console.error('调用设备工具失败:', networkErrorSummary(err));
                if (callback) callback(networkErrorResponse('调用设备工具失败'));
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
}

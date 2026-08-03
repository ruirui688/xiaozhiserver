import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

export default {
    // 获取音色
    getVoiceList(params, callback) {
        const queryParams = new URLSearchParams({
            ttsModelId: params.ttsModelId,
            page: params.page || 1,
            limit: params.limit || 10,
            name: params.name || ''
        }).toString();

        RequestService.sendRequest()
            .url(`${getServiceUrl()}/ttsVoice?${queryParams}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res.data || []);
            })
            .networkFail((err) => {
                console.error('获取音色列表失败:', err);
                RequestService.reAjaxFun(() => {
                    this.getVoiceList(params, callback);
                });
            }).send();
    },
    // 音色保存
    saveVoice(params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/ttsVoice`)
            .method('POST')
            .data(JSON.stringify({
                languages: params.languageType,
                name: params.voiceName,
                remark: params.remark,
                referenceAudio: params.referenceAudio,
                referenceText: params.referenceText,
                sort: params.sort,
                ttsModelId: params.ttsModelId,
                ttsVoice: params.voiceCode,
                voiceDemo: params.voiceDemo || ''
            }))
            .success((res) => {
                callback(res.data);
            })
            .networkFail((err) => {
                console.error('保存音色失败:', err);
                RequestService.reAjaxFun(() => {
                    this.saveVoice(params, callback);
                });
            }).send();
    },
    // 音色删除
    deleteVoice(ids, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/ttsVoice/delete`)
            .method('POST')
            .data(ids)
            .success((res) => {
                RequestService.clearRequestTime()
                callback(res);
            })
            .networkFail((err) => {
                console.error('删除音色失败:', err);
                RequestService.reAjaxFun(() => {
                    this.deleteVoice(ids, callback);
                });
            }).send();
    },
    // 音色修改
    updateVoice(params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/ttsVoice/${params.id}`)
            .method('PUT')
            .data(JSON.stringify({
                languages: params.languageType,
                name: params.voiceName,
                remark: params.remark,
                referenceAudio: params.referenceAudio,
                referenceText: params.referenceText,
                ttsModelId: params.ttsModelId,
                ttsVoice: params.voiceCode,
                voiceDemo: params.voiceDemo || ''
            }))
            .success((res) => {
                callback(res.data);
            })
            .networkFail((err) => {
                console.error('修改音色失败:', err);
                RequestService.reAjaxFun(() => {
                    this.updateVoice(params, callback);
                });
            }).send();
    },
    // 使用当前模型配置和指定音色执行真实 TTS 合成
    testVoice(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/ttsVoice/${id}/test`)
            .method('POST')
            .timeout(130000)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('测试音色失败:', err);
                RequestService.clearRequestTime();
                callback({
                    data: {
                        code: -1,
                        msg: err?.msg || '测试音色失败，请检查后台服务或网络连接'
                    }
                });
            }).send();
    }
}

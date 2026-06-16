import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

export default {
    getMusicList(params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/files`)
            .method('GET')
            .data(params)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getMusicList(params, callback);
                });
            }).send();
    },

    uploadMusic(formData, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/upload`)
            .method('POST')
            .header({})
            .data(formData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                RequestService.clearRequestTime();
                if (failCallback) {
                    failCallback(res);
                }
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.uploadMusic(formData, callback, failCallback);
                });
            }).send();
    },

    deleteMusic(path, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/files?path=${encodeURIComponent(path)}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((res) => {
                RequestService.clearRequestTime();
                if (failCallback) {
                    failCallback(res);
                }
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.deleteMusic(path, callback, failCallback);
                });
            }).send();
    },

    getPlayUrl(path) {
        return `${getServiceUrl()}/admin/music/play?path=${encodeURIComponent(path)}`;
    }
}

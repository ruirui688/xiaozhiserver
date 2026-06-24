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

    renameMusic(path, newName, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/files/name?path=${encodeURIComponent(path)}&newName=${encodeURIComponent(newName)}`)
            .method('PUT')
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
                    this.renameMusic(path, newName, callback, failCallback);
                });
            }).send();
    },

    updateMusicDescription(path, description, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/files/description?path=${encodeURIComponent(path)}&description=${encodeURIComponent(description || '')}`)
            .method('PUT')
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
                    this.updateMusicDescription(path, description, callback, failCallback);
                });
            }).send();
    },

    updateMusicMetadata(path, description, aliases, callback, failCallback) {
        const params = new URLSearchParams();
        params.append('path', path);
        params.append('description', description || '');
        (aliases || []).forEach((alias) => {
            params.append('aliases', alias);
        });
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/admin/music/files/metadata?${params.toString()}`)
            .method('PUT')
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
                    this.updateMusicMetadata(path, description, aliases, callback, failCallback);
                });
            }).send();
    },

    getPlayUrl(path) {
        return `${getServiceUrl()}/admin/music/play?path=${encodeURIComponent(path)}`;
    }
}

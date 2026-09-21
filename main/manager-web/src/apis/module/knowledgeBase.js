import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

/**
 * 获取认证token
 */
function getAuthToken() {
  return localStorage.getItem('token') || '';
}

/**
 * 通用API请求包装器
 * @param {Object} config - 请求配置
 * @param {string} config.url - 请求URL
 * @param {string} config.method - 请求方法
 * @param {Object} [config.data] - 请求数据
 * @param {Object} [config.headers] - 额外请求头
 * @param {Function} config.callback - 成功回调
 * @param {Function} [config.errorCallback] - 错误回调
 * @param {string} [config.errorMessage] - 错误消息
 * @param {Function} [config.retryFunction] - 重试函数
 */
function makeApiRequest(config) {
  const token = getAuthToken();
  const { url, method, data, headers, callback, errorCallback, retryFunction } = config;
  const canRetry = method === 'GET' && typeof retryFunction === 'function';

  const requestBuilder = RequestService.sendRequest()
    .url(url)
    .method(method)
    .header({
      'Authorization': `Bearer ${token}`,
      ...headers
    });

  if (data) {
    requestBuilder.data(data);
  }

  requestBuilder
    .success((res) => {
      RequestService.clearRequestTime();
      callback(res);
    })
    .fail((err) => {
      if (errorCallback) {
        errorCallback(err);
      }
    })
    .networkFail((err) => {
      if (canRetry) {
        RequestService.reAjaxFun(() => {
          retryFunction();
        });
      } else if (errorCallback) {
        errorCallback(err);
      }
    }).send();
}

/**
 * 知识库管理相关API
 */
export default {
  listEntries(datasetId, documentId, params, callback, errorCallback) {
    const query = new URLSearchParams(params).toString();
    makeApiRequest({ url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}/documents/${encodeURIComponent(documentId)}/entries?${query}`, method: 'GET', callback, errorCallback });
  },
  createEntry(datasetId, documentId, data, callback, errorCallback) {
    makeApiRequest({ url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}/documents/${encodeURIComponent(documentId)}/entries`, method: 'POST', data, headers: { 'Content-Type': 'application/json' }, callback, errorCallback });
  },
  updateEntry(datasetId, documentId, entryId, data, callback, errorCallback) {
    makeApiRequest({ url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}/documents/${encodeURIComponent(documentId)}/entries/${encodeURIComponent(entryId)}`, method: 'PUT', data, headers: { 'Content-Type': 'application/json' }, callback, errorCallback });
  },
  entryRevisions(datasetId, documentId, entryId, callback, errorCallback) {
    makeApiRequest({ url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}/documents/${encodeURIComponent(documentId)}/entries/${encodeURIComponent(entryId)}/revisions`, method: 'GET', callback, errorCallback });
  },
  restoreEntry(datasetId, documentId, entryId, data, callback, errorCallback) {
    makeApiRequest({ url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}/documents/${encodeURIComponent(documentId)}/entries/${encodeURIComponent(entryId)}/restore`, method: 'POST', data, headers: { 'Content-Type': 'application/json' }, callback, errorCallback });
  },
  getKnowledgeBase(datasetId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${encodeURIComponent(datasetId)}`,
      method: 'GET',
      callback,
      errorCallback
    });
  },

  /**
   * 获取知识库列表
   * @param {Object} params - 查询参数
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  getKnowledgeBaseList(params, callback, errorCallback) {
    const queryParams = new URLSearchParams({
      page: params.page,
      page_size: params.page_size,
      name: params.name || '',
      scope: params.scope || 'MY'
    }).toString();

    makeApiRequest({
      url: `${getServiceUrl()}/datasets?${queryParams}`,
      method: 'GET',
      callback: callback,
      errorCallback: errorCallback
    });
  },

  updatePublicationScope(datasetId, scope, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/publication`,
      method: 'PUT',
      data: { scope },
      headers: { 'Content-Type': 'application/json' },
      callback,
      errorCallback,
      errorMessage: '更新知识库发布范围失败'
    });
  },

  /**
   * 创建知识库
   * @param {Object} data - 知识库数据
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  createKnowledgeBase(data, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets`,
      method: 'POST',
      data: data,
      headers: { 'Content-Type': 'application/json' },
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '创建知识库失败'
    });
  },

  /**
   * 更新知识库
   * @param {string} datasetId - 知识库ID
   * @param {Object} data - 更新数据
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  updateKnowledgeBase(datasetId, data, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}`,
      method: 'PUT',
      data: data,
      headers: { 'Content-Type': 'application/json' },
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '更新知识库失败'
    });
  },

  /**
   * 删除单个知识库
   * @param {string} datasetId - 知识库ID
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  deleteKnowledgeBase(datasetId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}`,
      method: 'DELETE',
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '删除知识库失败'
    });
  },

  /**
   * 批量删除知识库
   * @param {string|Array} ids - 知识库ID字符串或数组
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  deleteKnowledgeBases(ids, callback, errorCallback) {
    // 确保ids是正确格式的字符串
    const idsStr = Array.isArray(ids) ? ids.join(',') : ids;

    makeApiRequest({
      url: `${getServiceUrl()}/datasets/batch?ids=${idsStr}`,
      method: 'DELETE',
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '批量删除知识库失败'
    });
  },

  /**
   * 获取文档列表
   * @param {string} datasetId - 知识库ID
   * @param {Object} params - 查询参数
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  getDocumentList(datasetId, params, callback, errorCallback) {
    const queryParams = new URLSearchParams({
      page: params.page,
      page_size: params.page_size,
      name: params.name || ''
    }).toString();

    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/documents?${queryParams}`,
      method: 'GET',
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '获取文档列表失败',
      retryFunction: () => this.getDocumentList(datasetId, params, callback, errorCallback)
    });
  },

  /**
   * 上传文档
   * @param {string} datasetId - 知识库ID
   * @param {Object} formData - 表单数据
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  uploadDocument(datasetId, formData, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/documents`,
      method: 'POST',
      data: formData,
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '上传文档失败'
    });
  },

  /**
   * 解析文档
   * @param {string} datasetId - 知识库ID
   * @param {string} documentId - 文档ID
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  parseDocument(datasetId, documentId, callback, errorCallback) {
    const requestBody = {
      document_ids: [documentId]
    };

    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/chunks`,
      method: 'POST',
      data: requestBody,
      headers: { 'Content-Type': 'application/json' },
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '解析文档失败'
    });
  },

  /**
   * 删除文档
   * @param {string} datasetId - 知识库ID
   * @param {string} documentId - 文档ID
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  deleteDocument(datasetId, documentId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/documents/${documentId}`,
      method: 'DELETE',
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '删除文档失败'
    });
  },

  /**
   * 获取文档切片列表
   * @param {string} datasetId - 知识库ID
   * @param {string} documentId - 文档ID
   * @param {Object} params - 查询参数
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  listChunks(datasetId, documentId, params, callback, errorCallback) {
    let queryParams = new URLSearchParams({
      page: params.page || 1,
      page_size: params.page_size || 10
    }).toString();

    // 添加关键词搜索参数
    if (params.keywords) {
      queryParams += `&keywords=${encodeURIComponent(params.keywords)}`;
    }

    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/documents/${documentId}/chunks?${queryParams}`,
      method: 'GET',
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '获取切片列表失败',
      retryFunction: () => this.listChunks(datasetId, documentId, params, callback, errorCallback)
    });
  },

  /**
   * 召回测试
   * @param {string} datasetId - 知识库ID
   * @param {Object} data - 召回测试参数
   * @param {Function} callback - 回调函数
   * @param {Function} errorCallback - 错误回调
   */
  retrievalTest(datasetId, data, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/retrieval-test`,
      method: 'POST',
      data: data,
      headers: { 'Content-Type': 'application/json' },
      callback: callback,
      errorCallback: errorCallback,
      errorMessage: '召回测试失败'
    });
  },

  getKnowledgeSources(datasetId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources`,
      method: 'GET',
      callback,
      errorCallback,
      retryFunction: () => this.getKnowledgeSources(datasetId, callback, errorCallback)
    });
  },

  createKnowledgeSource(datasetId, data, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources`,
      method: 'POST',
      data,
      headers: { 'Content-Type': 'application/json' },
      callback,
      errorCallback
    });
  },

  updateKnowledgeSource(datasetId, sourceId, data, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources/${sourceId}`,
      method: 'PUT',
      data,
      headers: { 'Content-Type': 'application/json' },
      callback,
      errorCallback
    });
  },

  deleteKnowledgeSource(datasetId, sourceId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources/${sourceId}`,
      method: 'DELETE',
      callback,
      errorCallback
    });
  },

  syncKnowledgeSource(datasetId, sourceId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources/${sourceId}/sync`,
      method: 'POST',
      callback,
      errorCallback
    });
  },

  getKnowledgeSourceRuns(datasetId, sourceId, callback, errorCallback) {
    makeApiRequest({
      url: `${getServiceUrl()}/datasets/${datasetId}/sources/${sourceId}/runs`,
      method: 'GET',
      callback,
      errorCallback,
      retryFunction: () => this.getKnowledgeSourceRuns(datasetId, sourceId, callback, errorCallback)
    });
  }

};

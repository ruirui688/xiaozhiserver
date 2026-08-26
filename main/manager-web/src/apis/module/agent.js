import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';


export default {
    // 获取智能体列表
    getAgentList(callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/list`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentList(callback);
                });
            }).send();
    },
    // 添加智能体
    addAgent(agentName, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent`)
            .method('POST')
            .data({ agentName: agentName })
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.addAgent(agentName, callback);
                });
            }).send();
    },
    // 删除智能体
    deleteAgent(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.deleteAgent(agentId, callback);
                });
            }).send();
    },
    // 获取智能体配置
    getDeviceConfig(agentId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 配置智能体
    updateAgentConfig(agentId, configData, callback, failCallback) {
        const request = RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}`)
            .method('PUT')
            .data(configData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            });
        if (failCallback) {
            request.fail(failCallback);
        }
        request
            .networkFail((err) => {
                if (failCallback) {
                    failCallback(err);
                    return;
                }
                RequestService.reAjaxFun(() => {
                    this.updateAgentConfig(agentId, configData, callback);
                });
            }).send();
    },
    // 新增方法：获取智能体模板
    getAgentTemplate(callback) {  // 移除templateName参数
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取模板失败:', err);
                RequestService.reAjaxFun(() => {
                    this.getAgentTemplate(callback);
                });
            }).send();
    },

    getAccessibleAgentTemplates(callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates`)
            .method('GET')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    getAgentTemplatePreview(templateId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}/preview`)
            .method('GET')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    createAgentFromTemplate(templateId, agentName, expectedVersion, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}/create`)
            .method('POST')
            .data({ agentName, expectedVersion })
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    saveAgentAsTemplate(agentId, payload, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/templates`)
            .method('POST')
            .data(payload)
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    applyAgentTemplate(agentId, templateId, expectedVersion, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/templates/${templateId}/apply`)
            .method('POST')
            .data({ expectedVersion })
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    deleteWorkspaceAgentTemplate(templateId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}`)
            .method('DELETE')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    submitAgentTemplateReview(templateId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}/submit-review`)
            .method('POST')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    getAgentTemplateReviewQueue(callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/review-queue`)
            .method('GET')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    reviewAgentTemplate(templateId, decision, note, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}/review`)
            .method('POST')
            .data({ decision, note })
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    publishOfficialAgentTemplate(templateId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/templates/${templateId}/publish-official`)
            .method('POST')
            .success((res) => callback(res))
            .fail((err) => failCallback && failCallback(err))
            .networkFail((err) => failCallback && failCallback(err))
            .send();
    },

    // 新增：获取智能体模板分页列表
    getAgentTemplatesPage(params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template/page`)
            .method('GET')
            .data(params)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取模板分页列表失败:', err);
                RequestService.reAjaxFun(() => {
                    this.getAgentTemplatesPage(params, callback);
                });
            }).send();
    },
    // 获取智能体会话列表
    getAgentSessions(agentId, params, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/sessions`)
            .method('GET')
            .data(params)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentSessions(agentId, params, callback);
                });
            }).send();
    },
    // 获取智能体聊天记录
    getAgentChatHistory(agentId, sessionId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/chat-history/${sessionId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentChatHistory(agentId, sessionId, callback);
                });
            }).send();
    },
    // 清空智能体聊天记录
    deleteAgentChatHistory(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/chat-history/${agentId}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                callback(err);
            }).send();
    },
    // 删除指定会话聊天记录
    deleteAgentChatSession(agentId, sessionId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/chat-history/${agentId}/session/${sessionId}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                callback(err);
            }).send();
    },
    // 删除单条聊天记录
    deleteAgentChatMessage(agentId, messageId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/chat-history/${agentId}/message/${messageId}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                callback(err);
            }).send();
    },
    // 获取音频下载ID
    getAudioId(audioId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/audio/${audioId}`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAudioId(audioId, callback);
                });
            }).send();
    },
    // 获取智能体的MCP接入点地址
    getAgentMcpAccessAddress(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/mcp/address/${agentId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                callback(err);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentMcpAccessAddress(agentId, callback);
                });
            }).send();
    },
    // 获取智能体的MCP工具列表
    getAgentMcpToolsList(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/mcp/tools/${agentId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentMcpToolsList(agentId, callback);
                });
            }).send();
    },
    // 获取智能体可视化 MCP HTTP 工具配置
    getAgentMcpNoCodeTools(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/mcp/no-code-tools/${agentId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentMcpNoCodeTools(agentId, callback);
                });
            }).send();
    },
    // 保存智能体可视化 MCP HTTP 工具配置
    saveAgentMcpNoCodeTools(agentId, tools, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/mcp/no-code-tools/${agentId}`)
            .method('PUT')
            .data(tools)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.saveAgentMcpNoCodeTools(agentId, tools, callback);
                });
            }).send();
    },
    getAgentFeishuConfig(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu`)
            .method('GET')
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    saveAgentFeishuConfig(agentId, config, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu`)
            .method('PUT')
            .data(config)
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    deleteAgentFeishuConfig(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu`)
            .method('DELETE')
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    testAgentFeishuConfig(agentId, config, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu/test`)
            .method('POST')
            .data(config)
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    getAgentFeishuChats(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu/chats`)
            .method('GET')
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    sendAgentFeishuTestMessage(agentId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/feishu/test-message`)
            .method('POST')
            .success((res) => callback(res))
            .networkFail((err) => callback(err))
            .send();
    },
    getAgentFeishuEventCallbackUrl(agentId) {
        return `${window.location.origin}${getServiceUrl()}/agent/${agentId}/feishu/events`;
    },
    // 添加智能体的声纹
    addAgentVoicePrint(voicePrintData, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print`)
            .method('POST')
            .data(voicePrintData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.addAgentVoicePrint(voicePrintData, callback);
                });
            }).send();
    },
    // 获取指定智能体声纹列表
    getAgentVoicePrintList(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/list/${id}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getAgentVoicePrintList(id, callback);
                });
            }).send();
    },
    // 删除智能体声纹
    deleteAgentVoicePrint(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/${id}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => callback(err))
            .networkFail((err) => callback(err))
            .send();
    },
    // 更新智能体声纹
    updateAgentVoicePrint(voicePrintData, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print`)
            .method('PUT')
            .data(voicePrintData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.updateAgentVoicePrint(voicePrintData, callback);
                });
            }).send();
    },
    // 创建设备声纹注册采集任务。
    createAgentVoicePrintEnrollment(enrollmentData, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/enrollments`)
            .method('POST')
            .data(enrollmentData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 查询声纹注册采集任务进度。
    getAgentVoicePrintEnrollment(agentId, enrollmentId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/enrollments/${enrollmentId}`)
            .method('GET')
            .data({ agentId })
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 向声纹注册采集任务追加已有语音样本。当前新增弹窗不再暴露该入口，保留接口兼容旧调用。
    appendAgentVoicePrintEnrollmentSamples(enrollmentId, sampleData, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/enrollments/${enrollmentId}/samples`)
            .method('POST')
            .data(sampleData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 完成声纹注册采集任务。
    completeAgentVoicePrintEnrollment(enrollmentId, completeData, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/enrollments/${enrollmentId}/complete`)
            .method('POST')
            .data(completeData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 取消尚未完成的设备声纹采集任务。
    cancelAgentVoicePrintEnrollment(enrollmentId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/voice-print/enrollments/${enrollmentId}/cancel`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback && callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 获取指定智能体用户类型聊天记录
    getRecentlyFiftyByAgentId(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${id}/chat-history/user`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getRecentlyFiftyByAgentId(id, callback);
                });
            }).send();
    },
    // 获取指定智能体用户类型聊天记录
    getContentByAudioId(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${id}/chat-history/audio`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getContentByAudioId(id, callback);
                });
            }).send();
    },
    // 在文件末尾（在最后一个方法后，大括号前）添加以下方法：
    // 新增智能体模板
    addAgentTemplate(templateData, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template`)
            .method('POST')
            .data(templateData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.addAgentTemplate(templateData, callback);
                });
            }).send();
    },

    // 更新智能体模板
    updateAgentTemplate(templateData, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template`)
            .method('PUT')
            .data(templateData)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.updateAgentTemplate(templateData, callback);
                });
            }).send();
    },

    // 删除智能体模板
    deleteAgentTemplate(id, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template/${id}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.deleteAgentTemplate(id, callback);
                });
            }).send();
    },

    // 批量删除智能体模板
    batchDeleteAgentTemplate(ids, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template/batch-remove`) // 修改为新的URL
            .method('POST')
            .data(Array.isArray(ids) ? ids : [ids]) // 确保是数组格式
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.batchDeleteAgentTemplate(ids, callback);
                });
            }).send();
    },
    // 在getAgentTemplate方法后添加获取单个模板的方法
    getAgentTemplateById(templateId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/template/${templateId}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail((err) => {
                console.error('获取单个模板失败:', err);
                RequestService.reAjaxFun(() => {
                    this.getAgentTemplateById(templateId, callback);
                });
            }).send();
    },

    // 获取聊天记录下载链接UUID
    getDownloadUrl(agentId, sessionId, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/chat-history/getDownloadUrl/${agentId}/${sessionId}`)
            .method('POST')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.getDownloadUrl(agentId, sessionId, callback);
                });
            }).send();
    },
    
    // 搜索智能体
    searchAgent(keyword, searchType, callback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/list?keyword=${encodeURIComponent(keyword)}&searchType=${searchType}`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .networkFail(() => {
                RequestService.reAjaxFun(() => {
                    this.searchAgent(keyword, searchType, callback);
                });
            }).send();
    },
    // 获取智能体标签
    getAgentTags(agentId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/tags`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 保存智能体标签
    saveAgentTags(agentId, tags, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/tags`)
            .method('PUT')
            .data(tags)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 获取智能体固定播报技能
    getAgentSkills(agentId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/skills`)
            .method('GET')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 新建智能体固定播报技能
    createAgentSkill(agentId, skill, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/skills`)
            .method('POST')
            .data(skill)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 更新智能体固定播报技能
    updateAgentSkill(agentId, skillId, skill, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/skills/${skillId}`)
            .method('PUT')
            .data(skill)
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 删除智能体固定播报技能
    deleteAgentSkill(agentId, skillId, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/skills/${skillId}`)
            .method('DELETE')
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
    // 批量保存技能排序
    saveAgentSkillOrder(agentId, items, callback, failCallback) {
        RequestService.sendRequest()
            .url(`${getServiceUrl()}/agent/${agentId}/skills/order`)
            .method('PUT')
            .data({ items })
            .success((res) => {
                RequestService.clearRequestTime();
                callback(res);
            })
            .fail((err) => {
                failCallback && failCallback(err);
            })
            .networkFail((err) => {
                failCallback && failCallback(err);
            }).send();
    },
}

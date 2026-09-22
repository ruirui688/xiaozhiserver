// Chat files remain authenticated: never put login tokens in URLs or media src.
export async function requestChatFile(baseUrl, path, tokenValue, { method = 'GET', signal, binary = false } = {}) {
    let token;
    try { token = JSON.parse(tokenValue).token; } catch (_) { /* handled below */ }
    if (!token) throw new Error('登录已过期，请重新登录');
    const headers = { Authorization: `Bearer ${token}`, 'Accept-Language': 'zh-CN' };
    const workspaceId = localStorage.getItem('currentWorkspaceId');
    if (workspaceId) headers['X-Workspace-Id'] = workspaceId;
    const response = await fetch(baseUrl + path, { method, headers, signal, redirect: 'error', cache: 'no-store' });
    if (response.status === 401) throw new Error('登录已过期，请重新登录');
    if (response.status === 403) throw new Error('没有权限访问此文件');
    if (response.status === 404) throw new Error('录音或下载链接不存在或已过期');
    if (!response.ok) throw new Error('文件请求失败，请稍后重试');
    if (!binary || (response.headers.get('content-type') || '').includes('json')) {
        const result = await response.json();
        if (result.code === 401) throw new Error('登录已过期，请重新登录');
        if (result.code !== 0 || !result.data || binary) throw new Error('文件不可用或没有访问权限，请重新获取');
        return result.data;
    }
    const blob = await response.blob();
    if (!blob.size) throw new Error('文件为空，无法播放或下载');
    return blob;
}

export function saveChatPackage(blob) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'chat-history-package.zip';
    document.body.appendChild(link);
    try { link.click(); } finally {
        link.remove();
        // Allow the browser to start consuming the download before releasing it.
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
}

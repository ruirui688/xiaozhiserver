const assert = require('node:assert/strict');
const { test, afterEach } = require('node:test');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const { requestChatFile } = loadModule('src/utils/chatHistoryFiles.js');
const originals = { fetch: global.fetch, localStorage: global.localStorage, Audio: global.Audio };
afterEach(() => Object.assign(global, originals));

test('ticket and binary requests carry login and workspace headers, not query tokens', async () => {
    global.localStorage = { getItem: () => 'workspace-test' };
    const calls = [];
    global.fetch = async (url, options) => {
        calls.push({ url, options });
        return options.method === 'POST'
            ? new Response(JSON.stringify({ code: 0, data: 'ticket' }), { headers: { 'content-type': 'application/json' } })
            : new Response(new Uint8Array([82, 73, 70, 70]), { headers: { 'content-type': 'application/octet-stream' } });
    };
    const token = JSON.stringify({ token: 'test-only-token' });
    assert.equal(await requestChatFile('/xiaozhi', '/agent/audio/id', token, { method: 'POST' }), 'ticket');
    const blob = await requestChatFile('/xiaozhi', '/agent/play/ticket', token, { binary: true });
    assert.deepEqual([...new Uint8Array(await blob.arrayBuffer())], [82, 73, 70, 70]);
    for (const { url, options } of calls) {
        assert.equal(options.headers.Authorization, 'Bearer test-only-token');
        assert.equal(options.headers['X-Workspace-Id'], 'workspace-test');
        assert.equal(options.redirect, 'error');
        assert.ok(!url.includes('token'));
    }
});

test('HTTP and JSON errors and empty files are never played as audio', async () => {
    global.localStorage = { getItem: () => null };
    for (const [response, pattern] of [
        [new Response('', { status: 401 }), /登录/],
        [new Response('', { status: 403 }), /权限/],
        [new Response('', { status: 404 }), /不存在/],
        [new Response('{"code":401}', { headers: { 'content-type': 'application/json' } }), /登录/],
        [new Response('{"code":500}', { headers: { 'content-type': 'application/json' } }), /不可用/],
        [new Response(''), /为空/],
    ]) {
        global.fetch = async () => response;
        await assert.rejects(requestChatFile('', '/file', '{"token":"test"}', { binary: true }), pattern);
    }
    await assert.rejects(requestChatFile('', '/file', null), /登录/);
});

function dialog() {
    const saved = [];
    const component = loadVueComponent('src/components/ChatHistoryDialog.vue', {
        '@/apis/api': { __esModule: true, default: {} },
        '@/utils/chatHistoryFiles': { requestChatFile, saveChatPackage: blob => saved.push(blob) },
    });
    const errors = [];
    const ctx = { ...component.data(), agentId: 'agent', currentSessionId: 'session', $message: { error: value => errors.push(value) } };
    for (const [name, fn] of Object.entries(component.methods)) ctx[name] = fn.bind(ctx);
    return { ctx, errors, saved, component };
}

test('original stored audio plays via Blob and releases resources when ended', async () => {
    const { ctx, errors } = dialog();
    const calls = [];
    ctx.requestFile = async (path, options) => {
        calls.push({ path, options });
        return options.binary ? new Blob(['original-recording']) : 'ticket';
    };
    global.Audio = class {
        constructor(src) { this.src = src; }
        async play() {}
        pause() { this.paused = true; }
        removeAttribute() {}
        load() {}
    };
    await ctx.playAudio({ audioId: 'recording' });
    assert.match(ctx.audioElement.src, /^blob:/);
    assert.equal(calls[0].path, '/agent/audio/recording');
    assert.equal(calls[1].path, '/agent/play/ticket');
    const url = ctx.audioUrl;
    assert.equal(await (await fetch(url)).text(), 'original-recording');
    ctx.audioElement.onended();
    assert.equal(ctx.playingAudioId, null);
    await assert.rejects(fetch(url));
    assert.deepEqual(errors, []);
});

test('failed fetch and rejected play both clear spinner and report an error', async () => {
    for (const failFetch of [true, false]) {
        const { ctx, errors } = dialog();
        ctx.requestFile = async (_, options) => {
            if (failFetch) throw new Error('没有权限');
            return options.binary ? new Blob(['audio']) : 'ticket';
        };
        global.Audio = class {
            async play() { throw new Error('播放被拒绝'); }
            pause() {} removeAttribute() {} load() {}
        };
        await ctx.playAudio({ audioId: 'id' });
        assert.equal(ctx.playingAudioId, null);
        assert.equal(ctx.audioUrl, null);
        assert.equal(errors.length, 1);
    }
});

test('late audio response cannot start playback after cancellation or replace a newer selection', async () => {
    const { ctx, errors } = dialog();
    const pending = [];
    ctx.requestFile = () => new Promise(resolve => pending.push(resolve));
    global.Audio = class { constructor() { throw new Error('must not play'); } };
    const first = ctx.playAudio({ audioId: 'first' });
    const second = ctx.playAudio({ audioId: 'second' });
    pending[0]('old-ticket');
    await first;
    assert.equal(ctx.playingAudioId, 'second');
    ctx.cancelFileRequests();
    pending[1]('late-ticket');
    await second;
    assert.equal(ctx.playingAudioId, null);
    assert.deepEqual(errors, []);
});

test('both download scopes fetch authenticated binary and suppress duplicate clicks', async () => {
    for (const scope of ['current', 'previous']) {
        const { ctx, saved } = dialog();
        const calls = [];
        ctx.requestFile = async (path, options) => {
            calls.push(path);
            return options.binary ? new Blob(['zip']) : 'ticket';
        };
        const result = ctx.downloadPackage(scope);
        await ctx.downloadPackage(scope);
        await result;
        assert.equal(calls.length, 2);
        assert.equal(calls[1], `/agent/chat-history/download/ticket/${scope}`);
        assert.equal(saved.length, 1);
        assert.equal(ctx.downloading, false);
    }
});

test('closed dialog cancels pending download without saving or showing stale errors', async () => {
    const { ctx, saved, errors, component } = dialog();
    let finish;
    ctx.requestFile = () => new Promise(resolve => { finish = resolve; });
    const result = ctx.downloadPackage('current');
    component.beforeDestroy.call(ctx);
    finish('ticket');
    await result;
    assert.equal(ctx.downloading, false);
    assert.equal(saved.length, 0);
    assert.deepEqual(errors, []);
});

test('download failure and timeout clear loading without saving an error document', async () => {
    for (const error of [new Error('没有权限'), Object.assign(new Error(), { name: 'AbortError' })]) {
        const { ctx, errors, saved } = dialog();
        ctx.requestFile = async () => { throw error; };
        await ctx.downloadPackage('previous');
        assert.equal(ctx.downloading, false);
        assert.equal(ctx.downloadRequest, null);
        assert.equal(saved.length, 0);
        assert.equal(errors.length, 1);
    }
});

test('decoder error and repeated click clear playback state', async () => {
    const { ctx, errors } = dialog();
    ctx.requestFile = async (_, options) => options.binary ? new Blob(['audio']) : 'ticket';
    global.Audio = class {
        async play() {} pause() {} removeAttribute() {} load() {}
    };
    await ctx.playAudio({ audioId: 'id' });
    ctx.audioElement.onerror();
    assert.equal(ctx.audioUrl, null);
    assert.equal(ctx.playingAudioId, null);
    assert.equal(errors.length, 1);
    await ctx.playAudio({ audioId: 'id' });
    await ctx.playAudio({ audioId: 'id' });
    assert.equal(ctx.playingAudioId, null);
    assert.equal(ctx.audioElement, null);
});

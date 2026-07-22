const assert = require("node:assert/strict");
const { describe, test } = require("node:test");
const { loadModule } = require("./helpers/loadVueComponent.cjs");

function loadKnowledgeApi() {
  const requests = [];
  const retries = [];
  const requestService = {
    sendRequest() {
      const request = {
        methodValue: "",
        urlValue: "",
        dataValue: undefined,
        successCallback: null,
        failCallback: null,
        networkFailCallback: null,
        url(value) { this.urlValue = value; return this; },
        method(value) { this.methodValue = value; return this; },
        header() { return this; },
        data(value) { this.dataValue = value; return this; },
        success(callback) { this.successCallback = callback; return this; },
        fail(callback) { this.failCallback = callback; return this; },
        networkFail(callback) { this.networkFailCallback = callback; return this; },
        send() { requests.push(this); return this; },
      };
      return request;
    },
    clearRequestTime() {},
    reAjaxFun(callback) {
      retries.push(callback);
    },
  };
  const exports = loadModule("src/apis/module/knowledgeBase.js", {
    "../api": { getServiceUrl: () => "http://manager.test" },
    "../httpRequest": { __esModule: true, default: requestService },
  });
  return { api: exports.default, requests, retries };
}

describe("knowledge API retry safety", () => {
  test("never replays write operations after an uncertain network failure", () => {
    global.localStorage = { getItem: () => "token" };
    const { api, requests, retries } = loadKnowledgeApi();
    const failures = [];
    const onFailure = error => failures.push(error);

    api.createKnowledgeBase({ name: "kb" }, () => {}, onFailure);
    api.updateKnowledgeBase("kb-1", { name: "updated" }, () => {}, onFailure);
    api.deleteKnowledgeBase("kb-1", () => {}, onFailure);
    api.deleteKnowledgeBases(["kb-1", "kb-2"], () => {}, onFailure);
    api.uploadDocument("kb-1", { file: "payload" }, () => {}, onFailure);
    api.parseDocument("kb-1", "doc-1", () => {}, onFailure);
    api.deleteDocument("kb-1", "doc-1", () => {}, onFailure);
    api.retrievalTest("kb-1", { question: "q" }, () => {}, onFailure);

    assert.equal(requests.length, 8);
    for (const request of requests)
      request.networkFailCallback(new Error(`uncertain ${request.methodValue}`));

    assert.equal(retries.length, 0);
    assert.equal(failures.length, 8);
    delete global.localStorage;
  });

  test("retains bounded retry delegation for read-only document queries", () => {
    global.localStorage = { getItem: () => "token" };
    const { api, requests, retries } = loadKnowledgeApi();

    api.getDocumentList("kb-1", { page: 1, page_size: 10 }, () => {}, () => {});
    api.listChunks("kb-1", "doc-1", { page: 1, page_size: 10 }, () => {}, () => {});
    for (const request of requests)
      request.networkFailCallback(new Error("offline"));

    assert.equal(requests.every(request => request.methodValue === "GET"), true);
    assert.equal(retries.length, 2);
    delete global.localStorage;
  });
});

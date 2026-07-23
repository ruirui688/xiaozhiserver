const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { describe, test } = require("node:test");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

describe("knowledge upload task UI", () => {
  test("submits files as background tasks and closes after acknowledgement", async () => {
    const api = {
      uploadDocument(datasetId, formData, success) {
        assert.equal(datasetId, "dataset-1");
        assert.ok(formData);
        success({ data: { code: 0, data: { id: "doc-1", run: "RUNNING", progress: 0.02 } } });
      },
    };
    const component = loadVueComponent("src/components/knowledge/FileUploadPanel.vue", {
      "@/apis/module/knowledgeBase": { __esModule: true, default: api },
    });
    const emitted = [];
    const notifications = [];
    const context = {
      selectedFilesList: [{ name: "manual.md", size: 7, raw: new Blob(["content"]) }],
      datasetId: "dataset-1",
      uploading: false,
      submitResult: null,
      internalVisible: true,
      $message: { error() {} },
      $notify(value) { notifications.push(value); },
      $emit(name, value) { emitted.push([name, value]); },
      $t(key, params = {}) {
        if (key === "knowledgeFileUpload.submittedDescription") return `submitted ${params.count}`;
        return key;
      },
    };

    component.methods.handleBatchUploadSubmit.call(context);
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(context.uploading, false);
    assert.equal(context.internalVisible, false);
    assert.equal(emitted[0][0], "upload-complete");
    assert.equal(emitted[0][1].acceptedCount, 1);
    assert.equal(notifications[0].type, "success");
    assert.equal(notifications[0].duration, 8000);
  });

  test("renders persistent progress and failure states in the document list", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/views/KnowledgeFileUpload.vue"),
      "utf8"
    );
    assert.match(source, /getProcessingStage\(scope\.row\)/);
    assert.match(source, /scope\.row\.error/);
    assert.match(source, /knowledgeFileUpload\.reupload/);
    assert.equal(source.includes("maxStatusPollingTime"), false);
  });
});

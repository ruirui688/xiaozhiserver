const fs = require("node:fs");
const Module = require("node:module");
const path = require("node:path");
const babel = require("@babel/core");
const { parseComponent } = require("vue-template-compiler");

const WEB_ROOT = path.resolve(__dirname, "../..");

function compileSource(filename, source, mocks, cache) {
  if (cache.has(filename)) return cache.get(filename).exports;
  const compiled = babel.transformSync(source, {
    filename,
    configFile: false,
    babelrc: false,
    presets: [[require.resolve("@babel/preset-env"), { targets: { node: "current" } }]],
    sourceMaps: "inline",
  }).code;
  const target = new Module(filename, module.parent);
  target.filename = filename;
  target.paths = Module._nodeModulePaths(path.dirname(filename));
  cache.set(filename, target);
  const nativeRequire = target.require.bind(target);
  target.require = (request) => {
    if (Object.prototype.hasOwnProperty.call(mocks, request)) return mocks[request];
    if (request.startsWith("@/")) {
      const resolved = path.join(WEB_ROOT, "src", request.slice(2));
      const filenameWithExtension = path.extname(resolved) ? resolved : `${resolved}.js`;
      if (filenameWithExtension.endsWith(".vue")) return { default: {} };
      return compileSource(
        filenameWithExtension,
        fs.readFileSync(filenameWithExtension, "utf8"),
        mocks,
        cache
      );
    }
    if (request.startsWith("./") || request.startsWith("../")) {
      const resolved = path.resolve(path.dirname(filename), request);
      const filenameWithExtension = path.extname(resolved) ? resolved : `${resolved}.js`;
      if (filenameWithExtension.endsWith(".vue")) return { default: {} };
      if (fs.existsSync(filenameWithExtension)) {
        return compileSource(
          filenameWithExtension,
          fs.readFileSync(filenameWithExtension, "utf8"),
          mocks,
          cache
        );
      }
    }
    return nativeRequire(request);
  };
  target._compile(compiled, filename);
  return target.exports;
}

function loadVueComponent(relativePath, mocks = {}) {
  const filename = path.join(WEB_ROOT, relativePath);
  const descriptor = parseComponent(fs.readFileSync(filename, "utf8"));
  const exports = compileSource(filename, descriptor.script.content, mocks, new Map());
  return exports.default || exports;
}

function loadModule(relativePath, mocks = {}) {
  const filename = path.join(WEB_ROOT, relativePath);
  return compileSource(filename, fs.readFileSync(filename, "utf8"), mocks, new Map());
}

module.exports = { loadModule, loadVueComponent };

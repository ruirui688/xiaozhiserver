本文档是开发类文档，如需部署小智服务端，[点击这里查看部署教程](../../docs/deployment/private-docker-release.md)

# xiaozhi

manager-web 是 Vue 2 + Vue CLI + Element UI 管理后台，默认开发端口 `8001`，接口通过 `/xiaozhi` 代理到 `manager-api` 的 `8002` 端口。

## Project setup

开发使用代码编辑器，导入项目时，选择`manager-web`文件夹作为项目目录

```
npm install
```

### Compiles and hot-reloads for development

```
npm run serve
```

也可以从项目根目录使用统一入口：

```
scripts/dev start web
```

### Compiles and minifies for production

```
npm run build
```

构建产物在 `dist/`，不要提交到 Git。用户入口为 `index.html`，员工从 `/ops/login` 访问独立的 `ops.html` 构建入口，两者会话与导航隔离；旧 `ops.html#/...` 链接兼容跳转。权限与服务额度边界见[平台服务与运营隔离](../../docs/architecture/platform-service-and-operations.md)。

### Customize configuration

See [Configuration Reference](https://cli.vuejs.org/config/).

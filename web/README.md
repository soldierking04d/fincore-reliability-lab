# 展示网站源码

公网入口保持为 https://124.223.164.254/ ，安全专题入口为 https://124.223.164.254/#runtime-security-casebook 。

- `fincore-demo-site-source.zip`：本次发布对应的完整网站工作区源码快照，包含构建配置、页面、静态资料与测试，不含依赖、构建产物、凭证或本地 Git 历史。
- `source-manifest.json`：快照内各文件 SHA256 与归档摘要。基线提交属于原本地网站仓库；当前快照包含其后已验证的修改。
- `runtime-security/`：便于直接审阅的安全专题组件与独立样式副本。
- 核心实验：[experiments/runtime-security](../experiments/runtime-security/README.md)。
- 实施手册：[PHP 运行时安全](../docs/security/php-runtime-2026-10.md)。

在新的独立目录解压网站源码，使用 Node.js 24 与 npm：

```sh
npm ci
npm test
npm run build
npm run dev -- --port 4398
```

静态资料与验证记录已经包含在快照中，构建不要求再次运行同步脚本。若要从本项目重新生成安全专题下载包，原同步脚本采用两个相邻目录 `fincore-demo-site` 与 `fincore-reliability-lab`；完整实验另需 Java、Python 3.7+、ASan clang++。

构建与静态导出是不同步骤。腾讯云采用 `scripts/export-cloud.mjs worker: outputs/cloud-web` 导出，与现有 Java 后端接口配合。源码中的 Sites 配置用于原项目构建，不代表授予任何托管发布权限。单独运行本地网站时，真实后端监控/实验接口需要对应后端服务。

本次安全实验展示本地规则、请求隔离和资源预算的可核对结果，不代表已经验证 PHP 引擎补丁或线上 PHP 主机。

# 安全专题页面组件

`runtime-security.tsx` 为专题入口，`runtime-security-deep-dive.tsx` 提供逐案拆解、拓扑图、跨语言结果与交接演练；`runtime-security.css` 是当前网站的专题样式。

组件从 `@/public/runtime-security/` 读取同一套规则、案例与验证记录。完整可构建网站见上一级的源码快照；这里保留原有导入路径便于与线上版本核对。

实验与规则维护在仓库 `experiments/runtime-security/`，完整说明见 `docs/security/php-runtime-2026-10.md`。页面不提供扫描、交易或生产配置修改接口。

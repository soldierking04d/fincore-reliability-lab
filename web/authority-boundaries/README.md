# 钱包权限边界与第三方供应链

在线展示：[完整模块](https://124.223.164.254/#authority-boundaries) · [详细签名时序](https://124.223.164.254/#authority-signing-detail) · [7702状态](https://124.223.164.254/#authority-7702-detail)。

- [业务与技术手册](../../docs/security/authority-boundaries-20261030.md)：事实等级、五条详细技术链路、权限撤销、恢复验收与实现范围。
- [防御实验](../../experiments/authority-boundaries/README.md)：11项Node规则测试、16项Java真实验签/并发断言、8个合成场景；没有钱包连接或链上交易广播。
- 本目录保留关键页面组件、详细消息定义与测试，供直接审阅；完整网站源码和逐文件摘要位于上一级`fincore-demo-site-source.zip`、`source-manifest.json`。
- 页面包含7张概览/时序/状态图。数据库原子预占、HSM/MPC、真实链操作属于明确标注的生产目标，不宣称已在实验实现。

此次发行只更新静态网站。历史后端测试、性能数据和既有AI证据保留原始验证范围；不开放公网模型调用或钱包操作。

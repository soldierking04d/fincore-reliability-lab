# 运行时安全实验

说明文档：[PHP 运行时漏洞专题](../../docs/security/php-runtime-2026-10.md)。资料复核日期 2026-10-29。

- `php-policy.mjs`：日期固定的离线核对规则与生命周期教学模型，页面共用源码。
- `php-policy.test.mjs`：版本边界、回补判断、Windows 包修订、配置与状态清理测试。
- `assess.mjs`：只读取本地资产 JSON；返回 0/2/64，含待办的示例预期返回 2。
- `inventory.example.json`：五条虚构资产，涵盖常见核验遗漏。
- `ThreadLocalIsolationDemo.java`：真实线程池中的负例与同步作用域修正，兼容 Java 8+。
- `verify.mjs`：验证规则、三种命令行退出码及 Java 编译执行，输出源码摘要与实际验证记录。
- `casebook.json`：四个案例的触发前提、执行步骤、修正对照与原始依据，供页面使用。
- `runtime-guards.mjs` / `.test.mjs`：Node 异步身份隔离、流式解压输入/输出预算与进程内并发预算。
- `context_isolation.py`：Python 协程负例与 ContextVar 修正，三项测试。
- `request_lifetime.cpp`：C++ 裸指针失效负例及弱引用修正，使用 ASan 检测。
- `release-gates.mjs` / `.test.mjs`：交接记录一致性验证，缺失 Worker、CLI 替代或旧代际不能通过。

运行：

```sh
node --test experiments/runtime-security/php-policy.test.mjs
node experiments/runtime-security/assess.mjs experiments/runtime-security/inventory.example.json
```

下载包解压后，在解压目录执行：

```sh
node --test php-policy.test.mjs runtime-guards.test.mjs release-gates.test.mjs
node verify.mjs
```

完整验证需要已有 Java、Python 3.7+、支持 ASan 的 clang++；本工具不安装依赖，缺失时明确失败，不把跳过当通过。Python 可用 RUNTIME_SECURITY_PYTHON 指定，C++ 可用 RUNTIME_SECURITY_CXX 指定。

Java 编译与执行方法见专题。PHP 自身未包含在此项目中；规则通过不代表主机已扫描或 PHP 引擎已修复。实验没有交易接口、凭证或网络探测。C++ 故意错误的局部所有权分支仅由验证器以 ASan 子进程执行。

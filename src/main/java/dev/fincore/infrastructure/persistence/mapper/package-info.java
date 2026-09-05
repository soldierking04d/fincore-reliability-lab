/**
 * MyBatis Mapper 原子操作接口模块。
 *
 * <p><strong>解决的问题：</strong>为账户、订单、成交、Inbox、Outbox、租约和对账提供可测试的
 * 数据库原子读写，避免业务服务依赖 JDBC 细节。</p>
 *
 * <p><strong>性能规则：</strong>热路径优先用单条条件更新、索引排序和批量参数降低往返；
 * {@code FOR UPDATE}、advisory lock 与 {@code SKIP LOCKED} 的范围必须写在方法注释中。不得为了
 * 少一次查询而跳过载荷核验或金融状态校验。</p>
 *
 * <p><strong>安全边界：</strong>所有参数使用 {@code #{...}} 预编译绑定，不接受外部请求提供的动态
 * SQL 片段；数据库返回行只是事实快照，不能在 Mapper 内决定业务成功。</p>
 */
package dev.fincore.infrastructure.persistence.mapper;

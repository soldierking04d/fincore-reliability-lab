/**
 * MyBatis 持久化适配层模块。
 *
 * <p><strong>解决的问题：</strong>把行锁、CAS、唯一约束、批量写入、SKIP LOCKED 和查询投影表达成
 * 可组合的数据库原子操作。</p>
 *
 * <p><strong>CPU 与 I/O：</strong>索引负责缩小候选集合，批量接口减少 JDBC 往返，Statement 级
 * MyBatis 本地缓存避免金融事务读取旧快照。SQL 优化必须同时检查执行计划、锁等待和数据库 CPU，
 * 不能只看应用 CPU。</p>
 *
 * <p><strong>边界：</strong>Mapper 不编排完整业务；事务边界、锁顺序、状态机和金融不变量仍由
 * application 层负责。</p>
 */
package dev.fincore.infrastructure.persistence;

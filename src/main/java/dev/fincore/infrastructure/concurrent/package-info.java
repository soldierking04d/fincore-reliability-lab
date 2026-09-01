/**
 * 高并发任务分片、背压、平台线程隔离与运行时指标实现。
 *
 * <p>该包只负责执行策略，不保存任何金融最终状态；数据库唯一约束、事务和围栏仍是
 * 正确性的最终边界。</p>
 */
package dev.fincore.infrastructure.concurrent;

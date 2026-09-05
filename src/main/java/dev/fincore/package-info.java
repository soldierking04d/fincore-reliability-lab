/**
 * FinCore Reliability Lab 根模块。
 *
 * <p><strong>解决的问题：</strong>验证金融交易系统在并发、重复投递、部分失败与 Worker
 * 接管时，订单、成交、余额和账本仍能收敛到唯一且可审计的结果。</p>
 *
 * <p><strong>模块划分：</strong>{@code web} 负责协议边界，{@code application} 负责事务编排，
 * {@code domain} 保存纯规则，{@code infrastructure} 适配数据库与执行资源，{@code messaging}
 * 负责可靠事件传播。依赖只能从入口指向业务和适配层，不能由 Mapper 反向编排业务。</p>
 *
 * <p><strong>性能边界：</strong>CPU、线程和批处理优化只能降低资源成本，不能替代数据库唯一约束、
 * 事务、不可变账本或 Epoch Fencing。任何优化都必须先保持金融不变量，再用指标和压测证明收益。</p>
 */
package dev.fincore;

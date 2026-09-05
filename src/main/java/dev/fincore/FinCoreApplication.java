package dev.fincore;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FinCore Reliability Lab 应用启动入口。
 *
 * <p><strong>解决的问题：</strong>统一装配 Web、MyBatis、Kafka、事务、监控和后台恢复任务，确保
 * 服务不会因为不同入口采用不同组件扫描或调度策略而产生旁路。</p>
 *
 * <p><strong>线程与 CPU：</strong>入口本身不创建线程。Spring MVC 的虚拟线程、撮合 Lane、Kafka
 * 平台线程和调度线程统一由 {@code ConcurrencyConfiguration} 配置；这里仅启用 Scheduling，驱动
 * Outbox 发布和超时抢占恢复，避免业务代码临时创建不可观测执行器。</p>
 *
 * <p><strong>安全边界：</strong>故障注入能力由独立的 {@code lab} Profile 控制，不会在默认环境加载；
 * 应用启动成功也不等于依赖健康，部署仍需通过数据库、Kafka 和 Actuator 健康检查。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@EnableScheduling
@MapperScan("dev.fincore.infrastructure.persistence.mapper")
@SpringBootApplication
public class FinCoreApplication {
    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 执行资源交给 Spring 生命周期管理，以便优雅停机时停止准入并等待在途事务收敛。
        SpringApplication.run(FinCoreApplication.class, args);
    }
}

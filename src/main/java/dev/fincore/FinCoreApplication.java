package dev.fincore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FinCore Reliability Lab 应用启动入口。
 *
 * <p>启用 Spring Scheduling 以驱动 Outbox 发布和超时抢占恢复任务。故障注入能力由
 * 独立的 {@code lab} Profile 控制，不会在默认环境加载。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@EnableScheduling
@SpringBootApplication
public class FinCoreApplication {
    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(FinCoreApplication.class, args);
    }
}

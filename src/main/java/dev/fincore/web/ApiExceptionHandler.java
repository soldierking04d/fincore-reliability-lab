package dev.fincore.web;

import dev.fincore.application.BusinessConflictException;
import dev.fincore.infrastructure.concurrent.ConcurrencyRejectedException;
import dev.fincore.infrastructure.concurrent.ConcurrencyTimeoutException;
import dev.fincore.messaging.MessageSubmissionException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST API 统一异常映射器。
 *
 * <p>参数错误返回 400，资源不存在返回 404，明确过载或消息依赖故障返回可重试状态；未分类的
 * {@link BusinessConflictException} 返回不可重试的 409；未分类的 {@link IllegalStateException}
 * 视为服务端内部状态错误并返回 500，不能把数据库竞争、序号分配或事务失败错误归因给客户端。
 * 内部异常只向日志写入，响应使用稳定错误码和关联编号，避免泄露实现细节。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /** 统一记录未预期服务端故障。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** 将 Kafka 接收失败或未知状态转换为可重试 503。 */
    @ExceptionHandler(MessageSubmissionException.class)
    ResponseEntity<Map<String, Object>> messagingUnavailable(MessageSubmissionException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "MESSAGE_SUBMISSION_UNAVAILABLE",
            "error", exception.getMessage(),
            "retryable", true
        ));
    }

    /** 将有界队列饱和转换为 429，提示调用方携带相同幂等键退避重试。 */
    @ExceptionHandler(ConcurrencyRejectedException.class)
    ResponseEntity<Map<String, Object>> overloaded(ConcurrencyRejectedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "CONCURRENCY_LIMIT_REACHED",
            "error", exception.getMessage(),
            "retryable", true
        ));
    }

    /** 将等待超时转换为 503；后台事务可能仍在完成，调用方必须先查询再重试。 */
    @ExceptionHandler(ConcurrencyTimeoutException.class)
    ResponseEntity<Map<String, Object>> timedOut(ConcurrencyTimeoutException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "CONCURRENCY_RESULT_UNKNOWN",
            "error", exception.getMessage(),
            "retryable", true
        ));
    }

    /**
     * 将调用参数错误转换为 400 响应。
     *
     * @param exception 原始运行时异常
     * @return 包含时间和错误原因的响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "INVALID_REQUEST",
            "error", exception.getMessage()
        ));
    }

    /**
     * 将确定性的权威状态冲突转换为 409，调用方应查询最新状态而不是盲目重试。
     *
     * @param exception 可安全展示的业务冲突
     * @return 不可重试的业务冲突响应
     */
    @ExceptionHandler(BusinessConflictException.class)
    ResponseEntity<Map<String, Object>> businessConflict(BusinessConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "BUSINESS_STATE_CONFLICT",
            "error", exception.getMessage(),
            "retryable", false
        ));
    }

    /**
     * 未分类的非法状态属于服务端错误，响应隐藏内部细节并提供可检索关联编号。
     *
     * @param exception 未预期的应用或基础设施状态错误
     * @return 不泄露内部异常信息的 500 响应
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> internalState(IllegalStateException exception) {
        String errorId = UUID.randomUUID().toString();
        LOGGER.error("Unclassified internal state error; errorId={}", errorId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", "INTERNAL_STATE_ERROR",
            "error", "internal state error",
            "errorId", errorId,
            "retryable", false
        ));
    }

    /**
     * 将数据库未找到记录异常转换为 404 响应。
     *
     * @param exception 数据访问层未找到记录异常
     * @return 标准资源不存在响应
     */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<Map<String, Object>> notFound(EmptyResultDataAccessException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of(
                "timestamp", Instant.now().toString(),
                "code", "RESOURCE_NOT_FOUND",
                "error", "resource not found"
            ));
    }
}

package dev.fincore.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST API 统一异常映射器。
 *
 * <p>领域参数错误和非法状态统一返回 400，资源不存在返回 404。异常不会被吞掉后
 * 伪装成成功响应，调用方可以依据 HTTP 状态明确判断处理结果。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * 将参数错误和非法状态转换为 400 响应。
     *
     * @param exception 原始运行时异常
     * @return 包含时间和错误原因的响应
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<Map<String, Object>> badRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of(
            "timestamp", Instant.now().toString(),
            "error", exception.getMessage()
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
            .body(Map.of("timestamp", Instant.now().toString(), "error", "resource not found"));
    }
}

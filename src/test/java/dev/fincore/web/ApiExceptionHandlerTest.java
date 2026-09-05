package dev.fincore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.fincore.application.BusinessConflictException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** REST 异常分类与信息最小化契约测试。 */
class ApiExceptionHandlerTest {
    /** 参数错误应归因于调用方，并保留可修正的参数原因。 */
    @Test
    void invalidArgumentReturnsBadRequest() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, Object>> response =
            handler.badRequest(new IllegalArgumentException("quantity must be positive"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", response.getBody().get("code"));
        assertEquals("quantity must be positive", response.getBody().get("error"));
    }

    /** 已终态订单等确定性业务冲突应返回 409，并明确禁止盲目重试。 */
    @Test
    void businessConflictReturnsConflict() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Map<String, Object>> response = handler.businessConflict(
            new BusinessConflictException("terminal order cannot be canceled: FILLED"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("BUSINESS_STATE_CONFLICT", response.getBody().get("code"));
        assertEquals(false, response.getBody().get("retryable"));
    }

    /** 未分类的内部状态不能伪装成 400，也不能把数据库诊断原文返回公网。 */
    @Test
    void internalStateReturnsSanitizedServerError() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        String sensitiveDiagnostic = "sequence allocation failed at internal_table";

        ResponseEntity<Map<String, Object>> response =
            handler.internalState(new IllegalStateException(sensitiveDiagnostic));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_STATE_ERROR", response.getBody().get("code"));
        assertFalse(response.getBody().toString().contains(sensitiveDiagnostic));
    }
}

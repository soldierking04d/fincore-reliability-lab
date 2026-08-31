/**
 * HTTP 接口与统一异常映射。
 *
 * <p>控制器只负责协议转换和输入边界，不承载资金事务，也不能绕过应用服务中的幂等与 Fencing 校验。</p>
 */
package dev.fincore.web;


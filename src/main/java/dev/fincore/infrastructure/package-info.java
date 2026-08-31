/**
 * 外部系统与技术实现适配层。
 *
 * <p>该层负责数据库、消息系统等基础设施细节，应用服务通过明确接口调用，避免在业务编排中
 * 直接使用 JDBC 或拼接 SQL。</p>
 */
package dev.fincore.infrastructure;

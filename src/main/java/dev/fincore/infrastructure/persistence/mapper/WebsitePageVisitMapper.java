package dev.fincore.infrastructure.persistence.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/**
 * 网站访问日志的独立持久化边界；单语句原子写入，事件重复时保持首次数据。
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-24
 */
public interface WebsitePageVisitMapper {
    /**
     * 单次只写服务已限定的至多 32 条，数据库慢于一秒即取消语句。
     * @param visits 净化后的有界事件批次
     * @return 实际插入条数，不包含重复事件
     */
    @Insert("""
        <script>
        INSERT INTO website_page_visit (event_id, client_ip, page_path, referrer_origin, user_agent, language)
        VALUES
        <foreach collection="visits" item="visit" separator=",">
            (#{visit.eventId,javaType=java.util.UUID,jdbcType=OTHER,
                typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
             CAST(#{visit.clientIp,jdbcType=VARCHAR} AS inet), #{visit.pagePath},
             #{visit.referrerOrigin,jdbcType=VARCHAR}, #{visit.userAgent,jdbcType=VARCHAR},
             #{visit.language,jdbcType=VARCHAR})
        </foreach>
        ON CONFLICT (event_id) DO NOTHING
        </script>
        """)
    @Options(timeout = 1)
    int insertBatch(@Param("visits") List<Visit> visits);

    /**
     * 每次最多删除指定条数的到期访问日志，不触及资金或其他业务表。
     * @param retentionDays 日志保留天数
     * @param limit 本轮最大删除条数
     * @return 实际删除条数
     */
    @Delete("""
        WITH expired AS (
            SELECT event_id FROM website_page_visit
            WHERE received_at < CURRENT_TIMESTAMP - #{retentionDays} * INTERVAL '1 day'
            ORDER BY received_at LIMIT #{limit} FOR UPDATE SKIP LOCKED
        )
        DELETE FROM website_page_visit WHERE event_id IN (SELECT event_id FROM expired)
        """)
    @Options(timeout = 1)
    int deleteExpired(@Param("retentionDays") int retentionDays, @Param("limit") int limit);

    /** 仅包含允许持久化的字段；不接收身份、Cookie、请求体或客户端时间。 */
    record Visit(UUID eventId, String clientIp, String pagePath, String referrerOrigin,
                 String userAgent, String language) {
    }
}

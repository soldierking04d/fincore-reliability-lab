package dev.fincore.infrastructure.persistence.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL {@code UUID} 与 Java {@link UUID} 之间的显式 MyBatis 类型处理器。
 *
 * <p><strong>解决的问题：</strong>动态 {@code foreach} 在生成 ParameterMapping 时需要明确元素属性
 * 类型，避免批量账本运行后才暴露 UUID 映射错误。</p>
 *
 * <p><strong>CPU 与分配优化：</strong>驱动按 PostgreSQL {@code OTHER} 直接发送/读取 UUID 对象，
 * 不先调用 {@code toString()} 再由数据库解析，减少字符串和字符数组分配，也保持索引参数类型稳定。</p>
 *
 * <p><strong>正确性边界：</strong>类型处理器只负责 JDBC 表示，不验证 UUID 的业务归属或锁顺序；
 * 这些规则仍由应用服务和数据库约束保证。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public final class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {
    /** 以 PostgreSQL UUID 原生对象写入预编译语句。 */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID parameter,
                                    JdbcType jdbcType) throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    /** 按列名读取 UUID。 */
    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getObject(columnName, UUID.class);
    }

    /** 按列序号读取 UUID。 */
    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getObject(columnIndex, UUID.class);
    }

    /** 从存储过程输出参数读取 UUID。 */
    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return statement.getObject(columnIndex, UUID.class);
    }
}

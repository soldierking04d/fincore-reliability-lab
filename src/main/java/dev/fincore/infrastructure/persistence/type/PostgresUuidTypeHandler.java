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
 * <p>普通单值参数可由 JDBC 驱动在运行时识别 UUID；但动态 {@code foreach} 会在生成
 * {@code ParameterMapping} 时提前校验元素属性类型。显式处理器既避免批量账本写入在启动后才暴露
 * 类型映射错误，也确保驱动按 PostgreSQL {@code OTHER} 类型发送参数，而不是先转成字符串。</p>
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

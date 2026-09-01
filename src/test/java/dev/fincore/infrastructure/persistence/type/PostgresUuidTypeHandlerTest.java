package dev.fincore.infrastructure.persistence.type;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.type.JdbcType;

/** PostgreSQL UUID 参数绑定回归测试。 */
class PostgresUuidTypeHandlerTest {
    /** 批量 SQL 中的 UUID 必须按数据库原生 OTHER 类型发送，不能隐式转成字符串。 */
    @Test
    void bindsUuidAsPostgresNativeType() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        UUID identifier = UUID.randomUUID();

        new PostgresUuidTypeHandler().setNonNullParameter(statement, 2, identifier, JdbcType.OTHER);

        verify(statement).setObject(2, identifier, Types.OTHER);
    }
}

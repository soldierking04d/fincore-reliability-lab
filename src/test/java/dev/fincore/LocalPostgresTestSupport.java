package dev.fincore;

import java.util.UUID;
import java.net.URI;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** 真实 PostgreSQL 回归；每个测试类独占临时 schema，仅使用合成数据。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LocalPostgresTestSupport {
    protected DataSource dataSource;
    protected JdbcTemplate jdbc;
    protected TransactionTemplate transactions;
    protected SqlSessionTemplate sessions;
    private String schema;
    private PostgreSQLContainer<?> postgres;

    /** 使用显式指定的本机隔离库或临时容器；CI 要求数据库时禁止静默跳过。 */
    @BeforeAll
    void openIsolatedSchema() throws Exception {
        schema = "settlement_test_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getProperty("fincore.test.postgres-url");
        String user = System.getProperty("fincore.test.postgres-user", "fincore_test");
        String password = System.getProperty("fincore.test.postgres-password", "");
        if (url == null || url.isBlank()) {
            boolean available = DockerClientFactory.instance().isDockerAvailable();
            if (!available && Boolean.getBoolean("fincore.test.require-database")) {
                throw new IllegalStateException("结算集成验收要求 PostgreSQL，禁止跳过后宣称通过");
            }
            Assumptions.assumeTrue(available,
                "真实 PostgreSQL 测试需要 Docker 或显式本机隔离测试库 fincore.test.postgres-url");
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        } else {
            String host = URI.create(url.substring("jdbc:".length())).getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host) && !"[::1]".equals(host)) {
                throw new IllegalArgumentException("PostgreSQL test URL must explicitly target a loopback test database");
            }
        }
        DriverManagerDataSource admin = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
        dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
            + "currentSchema=" + schema + "&ApplicationName=" + schema, user, password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.getTypeHandlerRegistry().register("dev.fincore.infrastructure.persistence.type");
        configuration.addMappers("dev.fincore.infrastructure.persistence.mapper");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        sessions = new SqlSessionTemplate(factory.getObject());
    }

    /** 仅清理本测试类创建的随机命名 schema，并关闭其拥有的临时容器。 */
    @AfterAll
    void closeIsolatedSchema() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
        if (postgres != null) {
            postgres.stop();
        }
    }
}

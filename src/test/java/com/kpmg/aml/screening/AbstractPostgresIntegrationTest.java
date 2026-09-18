package com.kpmg.aml.screening;

import com.kpmg.aml.screening.util.DataSourceConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all JPA / JDBC integration tests.
 *
 * <p>A single PostgreSQL container is started once per JVM and shared across
 * all subclasses (static {@code @Container} + Testcontainers' default reuse).
 * The schema is applied once per test class via {@code @Sql(BEFORE_TEST_CLASS)}
 * which executes outside the rolled-back test transaction, so tables persist
 * across all test methods within a class.
 *
 * <p>Subclasses annotated with {@code @Transactional} (via {@code @DataJpaTest})
 * roll back each test method automatically, so tests are isolated without a
 * per-test database truncation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DataSourceConfig.class)
@Sql(scripts = "/schema-test.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(
    scripts = "/truncate-test.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD,
    config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
)
public abstract class AbstractPostgresIntegrationTest {

    // The container is started once for the entire test suite and never stopped
    // by a JUnit extension (no @Testcontainers) so that @DynamicPropertySource
    // can safely call getJdbcUrl() for every test class context that is created.
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Primary (PostgreSQL) datasource — read by DataSourceConfig.postgresDataSource()
        // Uses jdbc-url (HikariCP property name) to match application.yml convention.
        registry.add("spring.datasource.postgres.jdbc-url",        POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.postgres.username",        POSTGRES::getUsername);
        registry.add("spring.datasource.postgres.password",        POSTGRES::getPassword);
        registry.add("spring.datasource.postgres.driver-class-name",
                     () -> "org.postgresql.Driver");

        // Oracle datasource — redirected to the same container for tests
        registry.add("spring.datasource.oracle.jdbc-url",         POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.oracle.username",         POSTGRES::getUsername);
        registry.add("spring.datasource.oracle.password",         POSTGRES::getPassword);
        registry.add("spring.datasource.oracle.driver-class-name",
                     () -> "org.postgresql.Driver");

        // Let Hibernate use the schema already created by schema-test.sql
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.database-platform",
                     () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}

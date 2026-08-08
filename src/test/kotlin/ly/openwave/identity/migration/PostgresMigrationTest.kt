package ly.openwave.identity.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.sql.DriverManager
import java.util.UUID

/**
 * Guards the production migration chain without making the ordinary test suite
 * depend on Docker or a developer-owned PostgreSQL process.
 *
 * The syntax check always runs. The real Flyway run is opt-in through a test
 * database URL and works with either a local PostgreSQL instance or a CI service
 * container. It creates and removes one random schema only.
 */
class PostgresMigrationTest {

    @Test
    fun `migration scripts do not contain MySQL-only DDL`() {
        val resources = PathMatchingResourcePatternResolver()
            .getResources("classpath*:db/migration/*.sql")

        assertThat(resources).isNotEmpty()

        val mysqlOnly = Regex(
            """(?i)\bAUTO_INCREMENT\b|\bENGINE\s*=|\bDEFAULT\s+CHARSET\b|\bUNSIGNED\b|\bTINYINT\b|`"""
        )

        resources.forEach { resource ->
            val sql = resource.inputStream.bufferedReader().use { it.readText() }
            val executableSql = sql.lineSequence()
                .map { line -> line.substringBefore("--") }
                .joinToString("\n")

            assertThat(mysqlOnly.find(executableSql))
                .describedAs("PostgreSQL migration %s contains MySQL-only syntax", resource.filename)
                .isNull()
        }
    }

    @Test
    fun `handle retirement migration keeps the permanent audit reservation`() {
        val sql = requireNotNull(javaClass.classLoader.getResourceAsStream(
            "db/migration/V18__handle_rename_and_retirement.sql"
        )).bufferedReader().use { it.readText() }

        assertThat(sql).contains(
            "BIGSERIAL",
            "TIMESTAMPTZ",
            "UNIQUE",
            "former_identity_id",
            "replaced_by_handle",
            "performed_by_bank",
            "retired_at",
            "chk_retired_handle_canonical"
        )
        assertThat(sql).doesNotContain("ON DELETE CASCADE")
    }

    @Test
    fun `scoped bank credential migration preserves legacy credential storage`() {
        val sql = requireNotNull(javaClass.classLoader.getResourceAsStream(
            "db/migration/V19__scoped_bank_api_credentials.sql"
        )).bufferedReader().use { it.readText() }

        assertThat(sql).contains(
            "CREATE TABLE bank_api_credentials",
            "CONSTRAINT fk_bank_api_credentials_bank",
            "FOREIGN KEY (bank_id) REFERENCES registered_banks (id) ON DELETE RESTRICT",
            "api_key_hash VARCHAR(64) NOT NULL UNIQUE",
            "ASTRO_REGISTRY",
            "revoked_at",
            "created_by"
        )
        assertThat(sql).doesNotContain("ALTER TABLE registered_banks")
    }

    @Test
    fun `legacy credential rotation migration is additive and permits full-bank overlap`() {
        val sql = requireNotNull(javaClass.classLoader.getResourceAsStream(
            "db/migration/V20__rotatable_legacy_bank_credentials.sql"
        )).bufferedReader().use { it.readText() }

        assertThat(sql).contains(
            "ADD COLUMN IF NOT EXISTS legacy_api_key_active BOOLEAN NOT NULL DEFAULT TRUE",
            "legacy_api_key_deactivated_at TIMESTAMPTZ",
            "DROP CONSTRAINT IF EXISTS chk_bank_api_credentials_scope",
            "'ASTRO_REGISTRY', 'FULL_BANK'"
        )
        assertThat(sql).doesNotContain("DROP COLUMN")
    }

    @Test
    @EnabledIfEnvironmentVariable(
        named = "OPENWAVE_TEST_POSTGRES_URL",
        matches = "jdbc:postgresql:.*"
    )
    fun `all Flyway migrations apply to an isolated PostgreSQL schema`() {
        val url = requireNotNull(System.getenv("OPENWAVE_TEST_POSTGRES_URL"))
        val username = System.getenv("OPENWAVE_TEST_POSTGRES_USER") ?: "postgres"
        val password = System.getenv("OPENWAVE_TEST_POSTGRES_PASSWORD") ?: ""
        val databaseName = url.substringBefore('?').substringAfterLast('/').lowercase()

        assumeTrue(
            databaseName.contains("test") || databaseName.contains("ci"),
            "Refusing to run migration cleanup unless the database name contains 'test' or 'ci'."
        )

        val schema = "openwave_flyway_${UUID.randomUUID().toString().replace("-", "")}" 
        val flyway = Flyway.configure()
            .dataSource(url, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .cleanDisabled(false)
            .load()

        try {
            val result = flyway.migrate()
            assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(20)
            flyway.validate()
        } finally {
            DriverManager.getConnection(url, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS \"$schema\" CASCADE")
                }
            }
        }
    }
}

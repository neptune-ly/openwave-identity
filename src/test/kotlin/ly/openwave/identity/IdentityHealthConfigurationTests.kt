package ly.openwave.identity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.HealthContributorRegistry
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:identity_health;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "registry.admin-key=test-admin-key",
        "spring.mail.host=203.0.113.1",
        "spring.mail.port=587"
    ]
)
@AutoConfigureMockMvc
class IdentityHealthConfigurationTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var healthContributors: HealthContributorRegistry
    @Autowired lateinit var environment: Environment

    @Test
    fun `aggregate health excludes optional SMTP but retains database health`() {
        assertThat(healthContributors.getContributor("mail")).isNull()
        assertThat(healthContributors.getContributor("db")).isNotNull()

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("UP"))
        }
    }

    @Test
    fun `SMTP operations use finite connection read and write timeouts`() {
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.connectiontimeout"))
            .isEqualTo("5000")
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.timeout"))
            .isEqualTo("5000")
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.writetimeout"))
            .isEqualTo("5000")
    }
}

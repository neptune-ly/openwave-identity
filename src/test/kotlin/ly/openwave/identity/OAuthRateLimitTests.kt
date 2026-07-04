package ly.openwave.identity

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:oauth_rate_limit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "registry.admin-key=test-admin-key",
        "oauth.token-rate-limit-per-minute=1",
        "oauth.introspection-rate-limit-per-minute=1",
        "oauth.revocation-rate-limit-per-minute=1",
        "oauth.rate-limit-max-entries=16"
    ]
)
@AutoConfigureMockMvc
class OAuthRateLimitTests {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `oauth public token endpoints fail fast after rate limit`() {
        val ip = "203.0.113.44"

        mockMvc.perform(
            post("/oauth/token")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/oauth/token")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
        ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))

        mockMvc.perform(
            post("/oauth/introspect")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "owat_test")
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/oauth/introspect")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "owat_test")
        ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))

        mockMvc.perform(
            post("/oauth/revoke")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "owat_test")
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/oauth/revoke")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", "owat_test")
        ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }
}

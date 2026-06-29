package ly.openwave.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.OAuthClientRepository
import ly.openwave.identity.repository.OAuthTokenRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:oauth_resolve_security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "registry.admin-key=test-admin-key"
    ]
)
@AutoConfigureMockMvc
class OAuthAndResolveSecurityTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var bankRepo: BankRepository
    @Autowired lateinit var identityRepo: IdentityRepository
    @Autowired lateinit var linkedAccountRepo: LinkedAccountRepository
    @Autowired lateinit var oauthClientRepo: OAuthClientRepository
    @Autowired lateinit var oauthTokenRepo: OAuthTokenRepository

    @BeforeEach
    fun reset() {
        oauthTokenRepo.deleteAll()
        oauthClientRepo.deleteAll()
        linkedAccountRepo.deleteAll()
        identityRepo.deleteAll()
        bankRepo.deleteAll()
        enableSwitch("oauth.global")
        enableSwitch("environment.sandbox")
        enableSwitch("owner.NEPTUNE")
    }

    @Test
    fun `empty scope on client_credentials grants nothing instead of full allowed set`() {
        val secret = createConfidentialClient(listOf("identity:registry.read", "identity:bank.aliases.read"))

        // Absent scope must be rejected, never widened to the client's full allowed set.
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
        ).andExpect(status().isBadRequest)

        // Explicitly requested scope is granted normally (and only that scope).
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("scope", "identity:registry.read")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.scope").value("identity:registry.read"))
    }

    @Test
    fun `introspect requires client authentication`() {
        val secret = createConfidentialClient(listOf("identity:registry.read"))
        val accessToken = issueToken(secret, "identity:registry.read")

        // No client credentials -> 401.
        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
        ).andExpect(status().isUnauthorized)

        // Wrong secret -> 401.
        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", CLIENT_ID)
                .param("client_secret", "ows_wrong")
        ).andExpect(status().isUnauthorized)

        // Authenticated client -> 200 active.
        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(true))
    }

    @Test
    fun `revoke requires client authentication`() {
        val secret = createConfidentialClient(listOf("identity:registry.read"))
        val accessToken = issueToken(secret, "identity:registry.read")

        mockMvc.perform(
            post("/oauth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
        ).andExpect(status().isUnauthorized)

        mockMvc.perform(
            post("/oauth/revoke")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.revoked").value(true))
    }

    @Test
    fun `authorized bank caller receives unmasked iban while anonymous caller is masked`() {
        val bankKey = registerBank("resolve-auth")
        claim(bankKey, "resolve-auth-user", "LY12345678901234567890")

        mockMvc.perform(get("/identity/resolve").queryParam("alias", "resolve-auth-user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.iban").value("****7890"))

        mockMvc.perform(
            get("/identity/resolve")
                .queryParam("alias", "resolve-auth-user")
                .header("X-OpenWave-Bank-Key", bankKey)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.iban").value("LY12345678901234567890"))
    }

    // --- helpers -------------------------------------------------------------

    private fun createConfidentialClient(scopes: List<String>): String {
        val response = mockMvc.perform(
            post("/oauth/admin/clients")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "clientId" to CLIENT_ID,
                            "displayName" to "Test Client",
                            "clientType" to "CONFIDENTIAL",
                            "ownerType" to "NEPTUNE",
                            "allowedScopes" to scopes,
                            "allowedEnvironments" to listOf("SANDBOX")
                        )
                    )
                )
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("client_secret").asText()
    }

    private fun issueToken(secret: String, scope: String): String {
        val response = mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("scope", scope)
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("access_token").asText()
    }

    private fun enableSwitch(key: String) {
        mockMvc.perform(
            post("/oauth/admin/kill-switches/{key}/enable", key)
                .header("X-OpenWave-Registry-Key", "test-admin-key")
        ).andExpect(status().isOk)
    }

    private fun registerBank(handle: String): String {
        val response = mockMvc.perform(
            post("/banks")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "bankHandle" to handle,
                            "displayName" to "$handle Bank",
                            "country" to "LY",
                            "coreUrl" to "https://$handle.example.test",
                            "contactEmail" to "ops@$handle.example.test"
                        )
                    )
                )
        ).andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).requiredText("bankApiKey")
    }

    private fun claim(bankKey: String, handle: String, iban: String) {
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bankKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "nptHandle" to handle,
                            "iban" to iban,
                            "customerDisplayName" to "Resolve Customer",
                            "bankCustomerRef" to "cust-$iban",
                            "setAsDefault" to true,
                            "nationalId" to "100000000099",
                            "phone" to "+218910009999",
                            "customerEmail" to "$handle@example.test"
                        )
                    )
                )
        ).andExpect(status().is2xxSuccessful)
    }

    private fun JsonNode.requiredText(field: String): String =
        get(field)?.asText() ?: error("Missing '$field' in $this")

    private fun MockHttpServletRequestBuilder.bankKey(apiKey: String) =
        header("X-OpenWave-Bank-Key", apiKey)

    companion object {
        private const val CLIENT_ID = "owc_test_client"
    }
}

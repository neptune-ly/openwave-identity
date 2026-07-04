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
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

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
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read", "identity:bank.aliases.read"))

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
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read"))
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
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read"))
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
    fun `replaying a rotated refresh token revokes the whole active token family`() {
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read"))

        // Initial issuance gives us refresh token RT1.
        val firstTokens = issueTokenPair(secret, "identity:registry.read")
        val rt1 = firstTokens.second

        // Legitimate rotation: RT1 -> (AT2, RT2). RT1 is now single-use/spent.
        val rotated = refresh(rt1, CLIENT_ID, secret)
        val at2 = rotated.first
        val rt2 = rotated.second

        // AT2 is active before the reuse event.
        introspectActive(secret, at2, expectedActive = true)

        // Attacker replays the already-rotated RT1 -> must fail closed (401)...
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("refresh_token", rt1)
        ).andExpect(status().isUnauthorized)

        // ...and the reuse must invalidate the whole active family: AT2 is now inactive...
        introspectActive(secret, at2, expectedActive = false)

        // ...and the legitimately-rotated RT2 can no longer be used either.
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("refresh_token", rt2)
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh token is bound to the issuing client`() {
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read"))
        val otherSecret = createConfidentialClient(
            clientId = "owc_other_client",
            scopes = listOf("identity:registry.read")
        )
        val refreshToken = issueTokenPair(secret, "identity:registry.read").second

        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", "owc_other_client")
                .param("client_secret", otherSecret)
                .param("refresh_token", refreshToken)
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authorization code with PKCE exchanges once and rejects replay`() {
        val secret = createConfidentialClient(
            scopes = listOf("openwave:owner.ops.read"),
            redirectUris = listOf("https://client.example.test/callback")
        )
        val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~abc"
        val challenge = pkceChallenge(verifier)

        val created = mockMvc.perform(
            get("/oauth/authorize")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", "https://client.example.test/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", "openwave:owner.ops.read")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", "state-123")
        ).andExpect(status().isFound)
            .andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).requiredText("request_id")

        val approved = mockMvc.perform(
            post("/oauth/consent-requests/{requestId}/approve", requestId)
                .header("X-OpenWave-Registry-Key", "test-admin-key")
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val redirectUrl = objectMapper.readTree(approved).requiredText("redirect_url")
        val code = queryParam(redirectUrl, "code")
        assertThat(queryParam(redirectUrl, "state")).isEqualTo("state-123")

        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("code", code)
                .param("redirect_uri", "https://client.example.test/callback")
                .param("code_verifier", verifier)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.scope").value("openwave:owner.ops.read"))

        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("code", code)
                .param("redirect_uri", "https://client.example.test/callback")
                .param("code_verifier", verifier)
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authorization code rejects redirect mismatch`() {
        val secret = createConfidentialClient(
            scopes = listOf("openwave:owner.ops.read"),
            redirectUris = listOf("https://client.example.test/callback")
        )
        val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~def"
        val challenge = pkceChallenge(verifier)

        mockMvc.perform(
            get("/oauth/authorize")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", "https://attacker.example.test/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", "openwave:owner.ops.read")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
        ).andExpect(status().isBadRequest)

        val code = approvedAuthorizationCode(challenge)
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("code", code)
                .param("redirect_uri", "https://client.example.test/other-callback")
                .param("code_verifier", verifier)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `authorization code rejects PKCE mismatch and plain challenge method`() {
        val secret = createConfidentialClient(
            scopes = listOf("openwave:owner.ops.read"),
            redirectUris = listOf("https://client.example.test/callback")
        )
        val verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~ghi"
        val challenge = pkceChallenge(verifier)

        mockMvc.perform(
            get("/oauth/authorize")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", "https://client.example.test/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", "openwave:owner.ops.read")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "plain")
        ).andExpect(status().isBadRequest)

        val code = approvedAuthorizationCode(challenge)
        mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("code", code)
                .param("redirect_uri", "https://client.example.test/callback")
                .param("code_verifier", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~bad")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `only resource server clients can introspect tokens owned by another client`() {
        val secret = createConfidentialClient(scopes = listOf("identity:registry.read"))
        val accessToken = issueToken(secret, "identity:registry.read")
        val otherSecret = createConfidentialClient(
            clientId = "owc_other_client",
            scopes = listOf("identity:registry.read")
        )
        val resourceSecret = createConfidentialClient(
            clientId = "owrs_astro",
            clientType = "RESOURCE_SERVER",
            scopes = listOf("openwave:tokens.introspect")
        )

        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", "owc_other_client")
                .param("client_secret", otherSecret)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))

        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", "owrs_astro")
                .param("client_secret", resourceSecret)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
    }

    @Test
    fun `creating a client with a dangerous redirect uri is rejected`() {
        listOf(
            "javascript:alert(1)",
            "http://*.evil.test/cb",
            "/relative/callback",
            "ftp://host.test/cb"
        ).forEach { badUri ->
            mockMvc.perform(
                post("/oauth/admin/clients")
                    .header("X-OpenWave-Registry-Key", "test-admin-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "displayName" to "Redirect Test",
                                "clientType" to "CONFIDENTIAL",
                                "ownerType" to "NEPTUNE",
                                "redirectUris" to listOf(badUri),
                                "allowedScopes" to listOf("identity:registry.read")
                            )
                        )
                    )
            ).andExpect(status().isBadRequest)
        }

        // A well-formed https redirect URI is accepted and stored.
        mockMvc.perform(
            post("/oauth/admin/clients")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "displayName" to "Redirect Test OK",
                            "clientType" to "CONFIDENTIAL",
                            "ownerType" to "NEPTUNE",
                            "redirectUris" to listOf("https://app.example.test/callback"),
                            "allowedScopes" to listOf("identity:registry.read")
                        )
                    )
                )
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.redirect_uris[0]").value("https://app.example.test/callback"))
    }

    @Test
    fun `security response headers are present on api responses`() {
        mockMvc.perform(get("/registry/info"))
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Frame-Options", "DENY"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
    }

    @Test
    fun `resolve retains its own short-lived cache-control and is not clobbered by global no-cache`() {
        val bankKey = registerBank("resolve-cache")
        claim(bankKey, "resolve-cache-user", "LY09876543210987654321")

        // ResolutionController deliberately sets Cache-Control: max-age=60 on the payment hot
        // path. The Batch-2 global security-headers block emits no-cache; assert it does NOT
        // clobber the controller's explicit, already-present caching directive.
        mockMvc.perform(get("/identity/resolve").queryParam("alias", "resolve-cache-user"))
            .andExpect(status().isOk)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60"))
            )
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

    private fun createConfidentialClient(
        clientId: String = CLIENT_ID,
        clientType: String = "CONFIDENTIAL",
        scopes: List<String>,
        redirectUris: List<String> = emptyList()
    ): String {
        val response = mockMvc.perform(
            post("/oauth/admin/clients")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "clientId" to clientId,
                            "displayName" to "Test Client",
                            "clientType" to clientType,
                            "ownerType" to "NEPTUNE",
                            "redirectUris" to redirectUris,
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

    private fun issueTokenPair(secret: String, scope: String): Pair<String, String> {
        val response = mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
                .param("scope", scope)
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val node = objectMapper.readTree(response)
        return node.get("access_token").asText() to node.get("refresh_token").asText()
    }

    private fun refresh(refreshToken: String, clientId: String, clientSecret: String): Pair<String, String> {
        val response = mockMvc.perform(
            post("/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("client_secret", clientSecret)
                .param("refresh_token", refreshToken)
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val node = objectMapper.readTree(response)
        return node.get("access_token").asText() to node.get("refresh_token").asText()
    }

    private fun introspectActive(secret: String, accessToken: String, expectedActive: Boolean) {
        mockMvc.perform(
            post("/oauth/introspect")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("token", accessToken)
                .param("client_id", CLIENT_ID)
                .param("client_secret", secret)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(expectedActive))
    }

    private fun approvedAuthorizationCode(challenge: String): String {
        val created = mockMvc.perform(
            get("/oauth/authorize")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", "https://client.example.test/callback")
                .queryParam("response_type", "code")
                .queryParam("scope", "openwave:owner.ops.read")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
        ).andExpect(status().isFound)
            .andReturn().response.contentAsString
        val requestId = objectMapper.readTree(created).requiredText("request_id")
        val approved = mockMvc.perform(
            post("/oauth/consent-requests/{requestId}/approve", requestId)
                .header("X-OpenWave-Registry-Key", "test-admin-key")
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        return queryParam(objectMapper.readTree(approved).requiredText("redirect_url"), "code")
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

    private fun pkceChallenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )

    private fun queryParam(url: String, key: String): String {
        val query = URI(url).rawQuery ?: error("No query in $url")
        return query.split("&")
            .map { it.substringBefore("=") to it.substringAfter("=", "") }
            .firstOrNull { it.first == key }
            ?.second
            ?: error("No $key in $url")
    }

    private fun MockHttpServletRequestBuilder.bankKey(apiKey: String) =
        header("X-OpenWave-Bank-Key", apiKey)

    companion object {
        private const val CLIENT_ID = "owc_test_client"
    }
}

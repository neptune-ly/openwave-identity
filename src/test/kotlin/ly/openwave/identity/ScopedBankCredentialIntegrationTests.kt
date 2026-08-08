package ly.openwave.identity

import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.entity.BankApiCredentialEntity
import ly.openwave.identity.entity.BankCredentialScope
import ly.openwave.identity.repository.BankApiCredentialRepository
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalAuditEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:scoped_bank_credentials;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class ScopedBankCredentialIntegrationTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var bankRepo: BankRepository
    @Autowired lateinit var credentialRepo: BankApiCredentialRepository
    @Autowired lateinit var identityRepo: IdentityRepository
    @Autowired lateinit var linkedAccountRepo: LinkedAccountRepository
    @Autowired lateinit var auditRepo: PortalAuditEventRepository

    @BeforeEach
    fun resetData() {
        linkedAccountRepo.deleteAll()
        identityRepo.deleteAll()
        credentialRepo.deleteAll()
        auditRepo.deleteAll()
        bankRepo.deleteAll()
    }

    @Test
    fun `legacy key remains full-bank compatible while Astro key is route scoped`() {
        val bank = registerBank("nub")
        val scoped = issue("nub", "astro-nub-registry")

        claim(bank.legacyKey, "legacy-nub", "LY11111111111111111111")
        claim(scoped.rawKey, "scoped-nub", "LY22222222222222222222")

        mockMvc.perform(get("/banks/me").bankKey(bank.legacyKey))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bankHandle").value("nub"))
        mockMvc.perform(get("/banks/me").bankKey(scoped.rawKey))
            .andExpect(status().isForbidden)
        mockMvc.perform(delete("/identity/scoped-nub").bankKey(scoped.rawKey))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            patch("/identity/scoped-nub/default-bank").bankKey(scoped.rawKey)
                .json(mapOf("bankHandle" to "nub"))
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/identity/accounts").bankKey(scoped.rawKey))
            .andExpect(status().isOk)
        mockMvc.perform(get("/identity/handles/another-nub/availability").bankKey(scoped.rawKey))
            .andExpect(status().isOk)
    }

    @Test
    fun `wrong revoked and cross-bank scoped credentials cannot authenticate or mutate`() {
        registerBank("andalus")
        registerBank("nub")
        val nubCredential = issue("nub", "astro-nub-registry")

        mockMvc.perform(post("/identity/claim").bankKey("owbk_nub_not-a-real-key").json(claimBody("wrong-key", "LY33333333333333333333")))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/banks/andalus/credentials/${nubCredential.id}/revoke").admin().contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/banks/nub/credentials/${nubCredential.id}/revoke").admin().contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
        mockMvc.perform(post("/identity/claim").bankKey(nubCredential.rawKey).json(claimBody("revoked-key", "LY44444444444444444444")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `issuance is admin-only one-time and audit-safe`() {
        registerBank("nub")
        mockMvc.perform(post("/banks/nub/credentials").json(mapOf("scope" to "ASTRO_REGISTRY", "label" to "astro-nub-registry")))
            .andExpect(status().isForbidden)

        val issued = issue("nub", "astro-nub-registry")
        val stored = credentialRepo.findById(issued.id).orElseThrow()
        assertThat(stored.apiKeyHash).doesNotContain(issued.rawKey)
        val audit = auditRepo.findAll().single { it.action == "BANK_CREDENTIAL_ISSUED" }
        assertThat(audit.action).isEqualTo("BANK_CREDENTIAL_ISSUED")
        assertThat(audit.details).doesNotContain(issued.rawKey)
        mockMvc.perform(get("/banks/nub/credentials").admin())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.credentials[0].bankApiKey").doesNotExist())
            .andExpect(jsonPath("$.credentials[0].apiKeyHash").doesNotExist())
    }

    @Test
    fun `credential hash uniqueness is enforced transactionally`() {
        val bank = registerBank("nub")
        val credential = BankApiCredentialEntity(
            bank = bank.entity,
            apiKeyHash = "a".repeat(64),
            scope = BankCredentialScope.ASTRO_REGISTRY,
            label = "first-credential"
        )
        credentialRepo.saveAndFlush(credential)
        assertThrows<DataIntegrityViolationException> {
            credentialRepo.saveAndFlush(
                BankApiCredentialEntity(
                    bank = bank.entity,
                    apiKeyHash = "a".repeat(64),
                    scope = BankCredentialScope.ASTRO_REGISTRY,
                    label = "duplicate-credential"
                )
            )
        }
    }

    @Test
    fun `full-bank replacement overlaps the legacy credential without a callable deactivation path`() {
        val bank = registerBank("andalus")
        // Issue a FULL_BANK key explicitly for the Nexus cutover and prove both
        // keys work concurrently. Deactivation is intentionally not exposed.
        val fullResponse = mockMvc.perform(
            post("/banks/andalus/credentials").admin().json(mapOf("scope" to "FULL_BANK", "label" to "nexus-full-bank-cutover"))
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val fullKey = objectMapper.readTree(fullResponse).requiredText("bankApiKey")
        claim(bank.legacyKey, "legacy-before-cutover", "LY55555555555555555555")
        claim(fullKey, "replacement-before-cutover", "LY66666666666666666666")

        mockMvc.perform(get("/banks/andalus/credentials").admin())
            .andExpect(jsonPath("$.legacyCredential.active").value(true))
        claim(bank.legacyKey, "legacy-after-overlap", "LY77777777777777777777")
        claim(fullKey, "replacement-after-cutover", "LY88888888888888888888")
    }

    private fun registerBank(handle: String): RegisteredBank {
        val response = mockMvc.perform(
            post("/banks").admin().json(
                mapOf(
                    "bankHandle" to handle,
                    "displayName" to handle.uppercase(),
                    "country" to "LY",
                    "coreUrl" to "https://$handle.example.test",
                    "contactEmail" to "ops@$handle.example.test"
                )
            )
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val body = objectMapper.readTree(response)
        return RegisteredBank(bankRepo.findByBankHandle(handle)!!, body.requiredText("bankApiKey"))
    }

    private fun issue(handle: String, label: String): IssuedCredential {
        val response = mockMvc.perform(
            post("/banks/$handle/credentials").admin().json(mapOf("scope" to "ASTRO_REGISTRY", "label" to label))
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.bankApiKey").exists())
            .andReturn().response.contentAsString
        val body = objectMapper.readTree(response)
        val rawKey = body.requiredText("bankApiKey")
        return IssuedCredential(body.get("credentialId").asLong(), rawKey)
    }

    private fun claim(apiKey: String, handle: String, iban: String) {
        mockMvc.perform(post("/identity/claim").bankKey(apiKey).json(claimBody(handle, iban)))
            .andExpect(status().isCreated)
    }

    private fun claimBody(handle: String, iban: String) = mapOf(
        "nptHandle" to handle,
        "iban" to iban,
        "customerDisplayName" to "Scoped Customer",
        "bankCustomerRef" to "cust-$handle",
        "nationalId" to ("1" + handle.hashCode().toUInt().toString().padStart(11, '0').takeLast(11)),
        "phone" to "+218910000000",
        "customerEmail" to "$handle@example.test"
    )

    private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String =
        get(name)?.asText() ?: error("missing $name")

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.admin() =
        header("X-OpenWave-Registry-Key", "test-admin-key")

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bankKey(value: String) =
        header("X-OpenWave-Bank-Key", value)

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.json(value: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(value))

    private data class RegisteredBank(val entity: ly.openwave.identity.entity.BankEntity, val legacyKey: String)
    private data class IssuedCredential(val id: Long, val rawKey: String)
}

package ly.openwave.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalEmailOtpRepository
import ly.openwave.identity.repository.PortalUserRepository
import ly.openwave.identity.security.PortalTokenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
        "spring.datasource.url=jdbc:h2:mem:identity_launch_hardening;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class IdentityLaunchHardeningTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var bankRepo: BankRepository
    @Autowired lateinit var identityRepo: IdentityRepository
    @Autowired lateinit var linkedAccountRepo: LinkedAccountRepository
    @Autowired lateinit var portalUserRepo: PortalUserRepository
    @Autowired lateinit var portalEmailOtpRepo: PortalEmailOtpRepository
    @Autowired lateinit var portalTokenService: PortalTokenService

    @BeforeEach
    fun resetData() {
        portalEmailOtpRepo.deleteAll()
        portalUserRepo.deleteAll()
        linkedAccountRepo.deleteAll()
        identityRepo.deleteAll()
        bankRepo.deleteAll()
    }

    @Test
    fun `bank A cannot edit bank B linked account by presenting its own bank handle`() {
        val bankA = registerBank("bank-a")
        val bankB = registerBank("bank-b")
        claim(bankA, "launch-user", "LY11111111111111111111", nationalId = "100000000001")
        claim(bankB, "launch-user", "LY22222222222222222222", nationalId = "100000000001")

        mockMvc.perform(
            patch("/identity/launch-user/accounts/iban/LY22222222222222222222")
                .bankKey(bankA.apiKey)
                .jsonBody(
                    mapOf(
                        "bankHandle" to "bank-a",
                        "newIban" to "LY99999999999999999999"
                    )
                )
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))

        val identity = identity("launch-user")
        assertThat(account(identity, "LY22222222222222222222")?.bankHandle).isEqualTo("bank-b")
        assertThat(account(identity, "LY99999999999999999999")).isNull()
    }

    @Test
    fun `account operations cannot rename global username or display name`() {
        val bank = registerBank("rename-bank")
        claim(bank, "stable-user", "LY33333333333333333333", displayName = "Stable Customer", nationalId = "100000000002")

        mockMvc.perform(
            patch("/identity/stable-user/accounts/iban/LY33333333333333333333")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "bankHandle" to "rename-bank",
                        "newIban" to "LY33333333333333333334",
                        "nptHandle" to "renamed-user",
                        "customerDisplayName" to "Renamed Customer"
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.iban").value("LY33333333333333333334"))

        val identity = identity("stable-user")
        assertThat(identity.nptHandle).isEqualTo("stable-user")
        assertThat(identity.displayName).isEqualTo("Stable Customer")
        assertThat(identityRepo.findByNptHandle("renamed-user")).isNull()
    }

    @Test
    fun `bank can manage only its own linked accounts`() {
        val bankA = registerBank("scope-a")
        val bankB = registerBank("scope-b")
        claim(bankA, "scoped-user", "LY44444444444444444441", nationalId = "100000000003")
        claim(bankB, "scoped-user", "LY55555555555555555551", nationalId = "100000000003")
        val identity = identity("scoped-user")
        val bankBAccount = account(identity, "LY55555555555555555551")!!

        mockMvc.perform(get("/identity/scoped-user/accounts/scope-b").bankKey(bankA.apiKey))
            .andExpect(status().isForbidden)

        mockMvc.perform(patch("/identity/accounts/${bankBAccount.id}/set-default").bankKey(bankA.apiKey))
            .andExpect(status().isNotFound)

        mockMvc.perform(delete("/identity/accounts/${bankBAccount.id}").bankKey(bankA.apiKey))
            .andExpect(status().isNotFound)

        mockMvc.perform(
            patch("/identity/scoped-user/accounts/iban/LY55555555555555555551/set-default")
                .queryParam("bankHandle", "scope-a")
                .bankKey(bankA.apiKey)
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            delete("/identity/scoped-user/accounts/iban/LY55555555555555555551")
                .queryParam("bankHandle", "scope-a")
                .bankKey(bankA.apiKey)
        ).andExpect(status().isNotFound)

        assertThat(account(identity("scoped-user"), "LY55555555555555555551")?.bankHandle).isEqualTo("scope-b")
    }

    @Test
    fun `default account and default bank behavior stays explicit and bank scoped`() {
        val bankA = registerBank("default-a")
        val bankB = registerBank("default-b")
        claim(bankA, "default-user", "LY66666666666666666661", setAsDefault = false, nationalId = "100000000004")

        val afterClaim = identity("default-user")
        assertThat(afterClaim.defaultBankHandle).isNull()
        assertThat(account(afterClaim, "LY66666666666666666661")?.isDefault).isTrue()
        mockMvc.perform(get("/identity/resolve").queryParam("alias", "default-user"))
            .andExpect(status().isNotFound)

        claim(bankB, "default-user", "LY77777777777777777771", setAsDefault = true, nationalId = "100000000004")
        val afterBankB = identity("default-user")
        assertThat(afterBankB.defaultBankHandle).isEqualTo("default-b")
        assertThat(account(afterBankB, "LY77777777777777777771")?.isDefault).isTrue()

        mockMvc.perform(
            patch("/identity/default-user/default-bank")
                .bankKey(bankA.apiKey)
                .jsonBody(mapOf("bankHandle" to "missing-bank"))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            patch("/identity/default-user/default-bank")
                .bankKey(bankA.apiKey)
                .jsonBody(mapOf("bankHandle" to "default-a"))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.defaultBankHandle").value("default-a"))

        mockMvc.perform(get("/identity/resolve").queryParam("alias", "default-user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bankHandle").value("default-a"))
            .andExpect(jsonPath("$.iban").value("LY66666666666666666661"))
            .andExpect(jsonPath("$.isDefault").value(true))
    }

    @Test
    fun `public resolution returns only routing facts`() {
        val bankA = registerBank("resolve-a")
        val bankB = registerBank("resolve-b")
        claim(bankA, "routing-user", "LY88888888888888888881", displayName = "Routing Customer", nationalId = "100000000005", phone = "+218910000005")
        claim(bankB, "routing-user", "LY99999999999999999991", displayName = "Ignored Name", setAsDefault = false, nationalId = "100000000005", phone = "+218910000005")

        val response = mockMvc.perform(get("/identity/resolve").queryParam("alias", "routing-user@resolve-b"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nptHandle").value("routing-user"))
            .andExpect(jsonPath("$.bankHandle").value("resolve-b"))
            .andExpect(jsonPath("$.iban").value("LY99999999999999999991"))
            .andExpect(jsonPath("$.displayName").value("Routing Customer"))
            .andReturn()
            .response
            .contentAsString

        val fields = objectMapper.readTree(response).fieldNames().asSequence().toSet()
        assertThat(fields).containsExactlyInAnyOrder("nptHandle", "bankHandle", "iban", "displayName", "isDefault", "resolvedAt")
        assertThat(response).doesNotContain("nationalId", "phone", "bankCustomerRef", "accounts", "linkedBanks")
    }

    @Test
    fun `internal phone lookup requires server to server registry key not portal admin session`() {
        val bank = registerBank("phone-sec")
        claim(
            bank,
            "phone-user",
            "LY10101010101010101010",
            displayName = "Phone Customer",
            nationalId = "100000000006",
            phone = "+218911000006"
        )
        val adminPortalSession = portalTokenService.issue(
            subject = "neptune.admin",
            role = "ADMIN",
            bankHandle = null,
            portalRole = "REGISTRY_ADMIN"
        )

        mockMvc.perform(
            get("/identity/internal/phone-lookup")
                .header("X-OpenWave-Portal-Session", adminPortalSession)
                .queryParam("phone", "+218911000006")
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            get("/identity/internal/phone-lookup")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .queryParam("phone", "+218911000006")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.resolved").value(true))
            .andExpect(jsonPath("$.accounts[0].iban").value("LY10101010101010101010"))
            .andExpect(jsonPath("$.accounts[0].ibanMasked").value("LY1010...1010"))
    }

    @Test
    fun `bank alias report csv is bank scoped and support safe`() {
        val bank = registerBank("csv-bank")
        claim(
            bank,
            "csv-user",
            "LY12121212121212121212",
            displayName = "CSV Customer",
            nationalId = "100000000007",
            phone = "+218912000007"
        )

        val body = mockMvc.perform(
            get("/identity/accounts")
                .bankKey(bank.apiKey)
                .queryParam("activeOnly", "false")
                .queryParam("format", "csv")
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertThat(body).contains("alias,alias_username,customer_ref,phone_masked,status")
        assertThat(body).contains("csv-user@csv-bank")
        assertThat(body).contains("LY1212...1212")
        assertThat(body).contains("***0007")
        assertThat(body).doesNotContain("LY12121212121212121212")
        assertThat(body).doesNotContain("+218912000007")
    }

    @Test
    fun `claim with customer email creates customer portal user and setup reset link`() {
        val bank = registerBank("customer-access")

        claim(
            bank,
            "email-user",
            "LY13131313131313131313",
            displayName = "Email Customer",
            nationalId = "100000000008",
            phone = "+218913000008",
            customerEmail = "customer@example.test"
        )

        val portalUser = portalUserRepo.findByUsername("email-user")
        assertThat(portalUser).isNotNull
        assertThat(portalUser!!.role.name).isEqualTo("CUSTOMER")
        assertThat(portalUser.email).isEqualTo("customer@example.test")
        assertThat(portalUser.bankHandle).isNull()
        val setupLink = portalEmailOtpRepo.findTopByUserAndPurposeOrderByCreatedAtDesc(
            portalUser,
            "PASSWORD_RESET_LINK"
        )
        assertThat(setupLink).isPresent
        assertThat(setupLink.get().codeHash).doesNotContain("customer@example.test")
        assertThat(setupLink.get().isValid()).isTrue()
    }

    @Test
    fun `bank portal can update only its own support safe branding profile`() {
        val bankA = registerBank("brand-a")
        registerBank("brand-b")

        mockMvc.perform(
            patch("/banks/me/branding")
                .bankKey(bankA.apiKey)
                .jsonBody(
                    mapOf(
                        "displayName" to "Brand A Identity",
                        "brandColor" to "#123ABC",
                        "supportEmail" to "support@brand-a.example.test",
                        "website" to "https://brand-a.example.test",
                        "coreUrl" to "https://ignored.example.test",
                        "active" to false
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.bankHandle").value("brand-a"))
            .andExpect(jsonPath("$.displayName").value("Brand A Identity"))
            .andExpect(jsonPath("$.branding.brand_color").value("#123ABC"))
            .andExpect(jsonPath("$.branding.support_email").value("support@brand-a.example.test"))
            .andExpect(jsonPath("$.branding.website").value("https://brand-a.example.test"))
            .andExpect(jsonPath("$.active").value(true))

        mockMvc.perform(
            patch("/banks/brand-b/branding")
                .bankKey(bankA.apiKey)
                .jsonBody(mapOf("displayName" to "Hijacked Bank"))
        ).andExpect(status().isForbidden)

        val updatedA = bankRepo.findByBankHandle("brand-a")!!
        val unchangedB = bankRepo.findByBankHandle("brand-b")!!
        assertThat(updatedA.displayName).isEqualTo("Brand A Identity")
        assertThat(updatedA.coreUrl).isEqualTo("https://brand-a.example.test")
        assertThat(updatedA.active).isTrue()
        assertThat(unchangedB.displayName).isEqualTo("brand-b Bank")
        assertThat(unchangedB.coreUrl).isEqualTo("https://brand-b.example.test")
    }

    @Test
    fun `bank portal my bank profile is authenticated and scoped`() {
        val bankA = registerBank("profile-a")
        registerBank("profile-b")

        mockMvc.perform(get("/banks/me"))
            .andExpect(status().isForbidden)

        mockMvc.perform(
            get("/banks/me")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
        ).andExpect(status().isForbidden)

        mockMvc.perform(get("/banks/me").bankKey(bankA.apiKey))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bankHandle").value("profile-a"))
            .andExpect(jsonPath("$.coreUrl").value("https://profile-a.example.test"))
            .andExpect(jsonPath("$.contactEmail").value("ops@profile-a.example.test"))
            .andExpect(jsonPath("$.branding.support_email").value("ops@profile-a.example.test"))
    }

    private fun registerBank(handle: String): BankCredentials {
        val body = mapOf(
            "bankHandle" to handle,
            "displayName" to "$handle Bank",
            "country" to "LY",
            "coreUrl" to "https://$handle.example.test",
            "contactEmail" to "ops@$handle.example.test"
        )
        val response = mockMvc.perform(
            post("/banks")
                .header("X-OpenWave-Registry-Key", "test-admin-key")
                .jsonBody(body)
        ).andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        return BankCredentials(handle, objectMapper.readTree(response).requiredText("bankApiKey"))
    }

    private fun claim(
        bank: BankCredentials,
        handle: String,
        iban: String,
        displayName: String = "Launch Customer",
        setAsDefault: Boolean = true,
        nationalId: String,
        phone: String = "+218910000000",
        customerEmail: String? = null
    ) {
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to handle,
                        "iban" to iban,
                        "customerDisplayName" to displayName,
                        "bankCustomerRef" to "cust-$iban",
                        "setAsDefault" to setAsDefault,
                        "nationalId" to nationalId,
                        "phone" to phone,
                        "customerEmail" to customerEmail
                    ).filterValues { it != null }
                )
        ).andExpect(status().is2xxSuccessful)
    }

    private fun identity(handle: String): IdentityEntity =
        identityRepo.findByNptHandle(handle) ?: error("Expected identity $handle")

    private fun account(identity: IdentityEntity, iban: String): LinkedAccountEntity? =
        linkedAccountRepo.findByIdentityIdAndIban(identity.id, iban)

    private fun JsonNode.requiredText(field: String): String =
        get(field)?.asText() ?: error("Missing '$field' in $this")

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bankKey(apiKey: String) =
        header("X-OpenWave-Bank-Key", apiKey)

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.jsonBody(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private data class BankCredentials(val handle: String, val apiKey: String)
}

package ly.openwave.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
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

    @BeforeEach
    fun resetData() {
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
        phone: String = "+218910000000"
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
                        "phone" to phone
                    )
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

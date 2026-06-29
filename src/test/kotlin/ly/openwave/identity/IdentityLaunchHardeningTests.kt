package ly.openwave.identity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.BankLoginChallengeStatus
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalBankLoginChallengeEntity
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.LinkedAccountRepository
import ly.openwave.identity.repository.PortalBankLoginChallengeRepository
import ly.openwave.identity.repository.PortalLoginChallengeRepository
import ly.openwave.identity.repository.PortalEmailOtpRepository
import ly.openwave.identity.repository.PortalUserRepository
import ly.openwave.identity.security.PortalTokenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.mockito.Mockito.verify
import ly.openwave.identity.service.PortalAuditService
import ly.openwave.identity.service.PortalCredentialNotificationService

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
    private val encoder = BCryptPasswordEncoder()

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var bankRepo: BankRepository
    @Autowired lateinit var identityRepo: IdentityRepository
    @Autowired lateinit var linkedAccountRepo: LinkedAccountRepository
    @Autowired lateinit var portalLoginChallengeRepo: PortalLoginChallengeRepository
    @Autowired lateinit var portalBankLoginChallengeRepo: PortalBankLoginChallengeRepository
    @Autowired lateinit var portalUserRepo: PortalUserRepository
    @Autowired lateinit var portalEmailOtpRepo: PortalEmailOtpRepository
    @Autowired lateinit var portalTokenService: PortalTokenService
    @Autowired lateinit var portalAuditService: PortalAuditService
    @MockBean lateinit var credentialNotificationService: PortalCredentialNotificationService

    @BeforeEach
    fun resetData() {
        portalBankLoginChallengeRepo.deleteAll()
        portalLoginChallengeRepo.deleteAll()
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
            .andExpect(jsonPath("$.iban").value("****6661"))
            .andExpect(jsonPath("$.isDefault").value(true))
    }

    @Test
    fun `public resolution returns only routing facts`() {
        val bankA = registerBank("resolve-a")
        val bankB = registerBank("resolve-b")
        claim(bankA, "routing-user", "LY88888888888888888881", displayName = "Routing Customer", nationalId = "100000000005", phone = "+218910000005")
        claim(bankB, "routing-user", "LY99999999999999999991", displayName = "Ignored Name", setAsDefault = false, nationalId = "100000000005", phone = "+218910000005")

        // Anonymous (public) callers receive a last-4 masked IBAN, never the full account number.
        val response = mockMvc.perform(get("/identity/resolve").queryParam("alias", "routing-user@resolve-b"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nptHandle").value("routing-user"))
            .andExpect(jsonPath("$.bankHandle").value("resolve-b"))
            .andExpect(jsonPath("$.iban").value("****9991"))
            .andExpect(jsonPath("$.displayName").value("Routing Customer"))
            .andReturn()
            .response
            .contentAsString

        val fields = objectMapper.readTree(response).fieldNames().asSequence().toSet()
        assertThat(fields).containsExactlyInAnyOrder("nptHandle", "bankHandle", "iban", "displayName", "isDefault", "resolvedAt")
        assertThat(response).doesNotContain("nationalId", "phone", "bankCustomerRef", "accounts", "linkedBanks")
        // The full unmasked IBAN must not leak to anonymous callers.
        assertThat(response).doesNotContain("LY99999999999999999991")
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
    fun `bank alias report search stays available for masked operational filters`() {
        val bank = registerBank("search-bank")
        claim(
            bank,
            "search-user",
            "LY16161616161616161616",
            displayName = "Search Customer",
            nationalId = "100000000020",
            phone = "+218916000020"
        )

        mockMvc.perform(
            get("/identity/accounts")
                .bankKey(bank.apiKey)
                .queryParam("activeOnly", "false")
                .queryParam("search", "search")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.bankHandle").value("search-bank"))
            .andExpect(jsonPath("$.aliases[0].alias").value("search-user@search-bank"))
            .andExpect(jsonPath("$.aliases[0].accounts[0].ibanMasked").value("LY1616...1616"))
    }

    @Test
    fun `claim with customer email creates customer portal user and setup reset link`() {
        val bank = registerBank("customer-access")

        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "email-user",
                        "iban" to "LY13131313131313131313",
                        "customerDisplayName" to "Email Customer",
                        "bankCustomerRef" to "cust-LY13131313131313131313",
                        "setAsDefault" to true,
                        "nationalId" to "100000000008",
                        "phone" to "+218913000008",
                        "customerEmail" to "customer@example.test"
                    )
                )
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.customerPortalAccess.username").value("email-user"))
            .andExpect(jsonPath("$.customerPortalAccess.userCreated").value(true))
            .andExpect(jsonPath("$.customerPortalAccess.emailConfigured").value(true))
            .andExpect(jsonPath("$.customerPortalAccess.passwordSetupLinkIssued").value(false))
            .andExpect(jsonPath("$.customerPortalAccess.nextStep").isNotEmpty)

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
        verify(credentialNotificationService).sendLinkedAccountNotice(
            "customer@example.test",
            "Email Customer",
            "email-user",
            "customer-access Bank",
            "customer-access",
            "LY13131313131313131313",
            "Identity enrolled and first account linked"
        )
    }

    @Test
    fun `bank portal login approvals list stays scoped to the current bank`() {
        val bankA = registerBank("approval-a")
        val bankB = registerBank("approval-b")
        val customerRef = "cust-approval-a"
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bankA.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "approval-user",
                        "iban" to "LY18181818181818181818",
                        "customerDisplayName" to "Approval Customer",
                        "bankCustomerRef" to customerRef,
                        "setAsDefault" to true,
                        "nationalId" to "100000000031",
                        "phone" to "+218918000031",
                        "customerEmail" to "approval-user@example.test"
                    )
                )
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bankB.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "approval-user",
                        "iban" to "LY19191919191919191919",
                        "customerDisplayName" to "Approval Customer",
                        "bankCustomerRef" to "cust-approval-b",
                        "setAsDefault" to false,
                        "nationalId" to "100000000031",
                        "phone" to "+218918000031",
                        "customerEmail" to "approval-user@example.test"
                    )
                )
        ).andExpect(status().isOk)

        val user = portalUserRepo.findByUsername("approval-user")!!
        val bankSession = portalTokenService.issue("approval.bank", "BANK", "approval-a", PortalRole.BANK_ADMIN.name)

        user.passwordHash = encoder.encode("bank-login-password")
        portalUserRepo.save(user)

        mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "+218918000031",
                        "password" to "bank-login-password",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)

        val challengeId = portalBankLoginChallengeRepo.findAll().single().id

        mockMvc.perform(
            get("/identity/login-approvals")
                .header("X-OpenWave-Portal-Session", bankSession)
                .queryParam("status", "PENDING")
                .queryParam("search", customerRef)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.pending").value(1))
            .andExpect(jsonPath("$.items[0].challenge_id").value(challengeId))
            .andExpect(jsonPath("$.items[0].bank_customer_ref").value(customerRef))
            .andExpect(jsonPath("$.items[0].requested_alias").value("approval-user"))

        mockMvc.perform(
            get("/identity/login-approvals/{challengeId}", challengeId)
                .header("X-OpenWave-Portal-Session", bankSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge_id").value(challengeId))
            .andExpect(jsonPath("$.bank_customer_ref").value(customerRef))
            .andExpect(jsonPath("$.requested_alias").value("approval-user"))
    }

    @Test
    fun `registry admin audit ledger exposes list and dedicated event detail`() {
        portalAuditService.record(null, "BANK_CREATED", "BANK", "audit-bank", mapOf("support_email" to "ops@audit-bank.test"))
        val eventId = (portalAuditService.list(10, null, null).first()["id"] as Number).toLong()
        val adminSession = portalTokenService.issue(
            subject = "identity.admin",
            role = "ADMIN",
            bankHandle = null,
            portalRole = PortalRole.REGISTRY_ADMIN.name
        )

        mockMvc.perform(
            get("/portal/audit-events")
                .header("X-OpenWave-Portal-Session", adminSession)
                .queryParam("limit", "10")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].action").value("BANK_CREATED"))
            .andExpect(jsonPath("$.events[0].entity_type").value("BANK"))
            .andExpect(jsonPath("$.events[0].entity_id").value("audit-bank"))

        mockMvc.perform(
            get("/portal/audit-events/{id}", eventId)
                .header("X-OpenWave-Portal-Session", adminSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.event.id").value(eventId))
            .andExpect(jsonPath("$.event.action").value("BANK_CREATED"))
            .andExpect(jsonPath("$.event.entity_type").value("BANK"))
            .andExpect(jsonPath("$.event.entity_id").value("audit-bank"))
    }

    @Test
    fun `customer portal aliases expose raw customer owned contact and account data`() {
        val bank = registerBank("customer-portal")

        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "portal-user",
                        "iban" to "LY17171717171717171717",
                        "customerDisplayName" to "Portal Customer",
                        "bankCustomerRef" to "cust-LY17171717171717171717",
                        "setAsDefault" to true,
                        "nationalId" to "100000000021",
                        "phone" to "+218917000021",
                        "customerEmail" to "portal-user@example.test"
                    )
                )
        ).andExpect(status().isCreated)

        val customerSession = portalTokenService.issue(
            subject = "portal-user",
            role = "CUSTOMER",
            bankHandle = null,
            portalRole = PortalRole.CUSTOMER.name
        )

        mockMvc.perform(
            get("/customer/aliases")
                .header("X-OpenWave-Portal-Session", customerSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.npt_handle").value("portal-user"))
            .andExpect(jsonPath("$.display_name").value("Portal Customer"))
            .andExpect(jsonPath("$.national_id").value("100000000021"))
            .andExpect(jsonPath("$.phone").value("218917000021"))
            .andExpect(jsonPath("$.phone_masked").value("***0021"))
            .andExpect(jsonPath("$.email").value("portal-user@example.test"))
            .andExpect(jsonPath("$.email_masked").value("po***@example.test"))
            .andExpect(jsonPath("$.created_at").exists())
            .andExpect(jsonPath("$.updated_at").exists())
            .andExpect(jsonPath("$.accounts[0].account_id").isNumber)
            .andExpect(jsonPath("$.accounts[0].bank_handle").value("customer-portal"))
            .andExpect(jsonPath("$.accounts[0].bank_display_name").value("customer-portal Bank"))
            .andExpect(jsonPath("$.accounts[0].bank_customer_ref").value("cust-LY17171717171717171717"))
            .andExpect(jsonPath("$.accounts[0].alias").value("portal-user@customer-portal"))
            .andExpect(jsonPath("$.accounts[0].iban").value("LY17171717171717171717"))
            .andExpect(jsonPath("$.accounts[0].iban_masked").value("LY1717...1717"))
            .andExpect(jsonPath("$.accounts[0].updated_at").exists())
            .andExpect(jsonPath("$.accounts[0].default").value(true))
    }

    @Test
    fun `claim requires customer email for digital identity enrollment`() {
        val bank = registerBank("cust-email")

        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "missing-email-user",
                        "iban" to "LY14141414141414141414",
                        "customerDisplayName" to "Email Missing",
                        "bankCustomerRef" to "cust-LY14141414141414141414",
                        "setAsDefault" to true,
                        "nationalId" to "100000000018",
                        "phone" to "+218914000018"
                    )
                )
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("customerEmail")))

        assertThat(identityRepo.findByNptHandle("missing-email-user")).isNull()
        assertThat(portalUserRepo.findByUsername("missing-email-user")).isNull()
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

        claim(
            bankA,
            "profile-customer",
            "LY83027101101104155801016",
            displayName = "Profile Customer",
            nationalId = "119876543210",
            phone = "218911000111",
            customerEmail = "profile-customer@example.test"
        )
        val identity = identity("profile-customer")
        val portalUser = portalUserRepo.save(
            PortalUserEntity(
                username = "profile-a.operator",
                passwordHash = encoder.encode("Password123!"),
                role = PortalRole.BANK_OPERATOR,
                bankHandle = "profile-a",
                displayName = "Profile A Operator",
                email = "profile-a.operator@example.test",
                active = true
            )
        )
        portalBankLoginChallengeRepo.save(
            PortalBankLoginChallengeEntity(
                id = "profile-approval-1",
                user = portalUser,
                identity = identity,
                identifierType = "PHONE",
                identifierHint = "***0111",
                status = BankLoginChallengeStatus.PENDING,
                expiresAt = Instant.now().plusSeconds(300)
            )
        )

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
            .andExpect(jsonPath("$.operationsPackage.customer_registry.linked_customer_count").value(1))
            .andExpect(jsonPath("$.operationsPackage.customer_registry.linked_account_count").value(1))
            .andExpect(jsonPath("$.operationsPackage.login_approvals.pending").value(1))
            .andExpect(jsonPath("$.operationsPackage.portal_access.active_portal_user_count").value(1))
            .andExpect(jsonPath("$.operationsPackage.trust.bank_vouched_identity_participant").value(true))
    }

    @Test
    fun `registry portal overview exposes scoped operations package`() {
        val bank = registerBank("overview-bank")
        claim(
            bank,
            "overview-user",
            "LY8311111111111111111111111",
            displayName = "Overview Customer",
            nationalId = "100000000021",
            phone = "+218911000021",
            customerEmail = "overview-user@example.test"
        )
        val adminSession = portalTokenService.issue(
            subject = "neptune.admin",
            role = "ADMIN",
            bankHandle = null,
            portalRole = "REGISTRY_ADMIN"
        )

        mockMvc.perform(
            get("/portal/overview")
                .header("X-OpenWave-Portal-Session", adminSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.package.registry.registered_banks").value(1))
            .andExpect(jsonPath("$.package.registry.active_identities").value(1))
            .andExpect(jsonPath("$.package.portal_access.customer_users").value(1))
            .andExpect(jsonPath("$.package.queues.banks_missing_brand_signal").value(1))
            .andExpect(jsonPath("$.package.readiness.total").value(6))

        val customerSession = portalTokenService.issue(
            subject = "overview-user",
            role = "CUSTOMER",
            bankHandle = null,
            portalRole = "CUSTOMER"
        )

        mockMvc.perform(
            get("/portal/overview")
                .header("X-OpenWave-Portal-Session", customerSession)
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `customer portal login can require totp after password sign in`() {
        val user = portalUserRepo.save(
            PortalUserEntity(
                username = "totp-user",
                passwordHash = encoder.encode("Password123!"),
                role = PortalRole.CUSTOMER,
                displayName = "Totp User",
                email = "totp-user@example.test"
            )
        )
        val portalSession = portalTokenService.issue(user.username, "CUSTOMER", null, PortalRole.CUSTOMER.name)

        val setupResponse = mockMvc.perform(
            post("/auth/totp/setup")
                .header("X-OpenWave-Portal-Session", portalSession)
        ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val secret = objectMapper.readTree(setupResponse).requiredText("secret")
        val initialCode = totpCode(secret)

        mockMvc.perform(
            post("/auth/totp/confirm")
                .header("X-OpenWave-Portal-Session", portalSession)
                .jsonBody(mapOf("code" to initialCode))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))

        val pending = mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "totp-user",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_required").value(true))
            .andExpect(jsonPath("$.mfa_method").value("TOTP"))
            .andReturn()
            .response
            .contentAsString

        val challengeId = objectMapper.readTree(pending).requiredText("challenge_id")
        mockMvc.perform(
            post("/auth/login/totp/verify")
                .jsonBody(
                    mapOf(
                        "challengeId" to challengeId,
                        "code" to totpCode(secret)
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.sessionToken").isNotEmpty)

        val refreshed = portalUserRepo.findByUsername("totp-user")
        assertThat(refreshed?.lastLoginAt).isNotNull
    }

    @Test
    fun `customer portal login accepts email or national id as customer identifier`() {
        val bank = registerBank("customer-login")
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "customer-login-user",
                        "iban" to "LY15151515151515151515",
                        "customerDisplayName" to "Login Customer",
                        "bankCustomerRef" to "cust-LY15151515151515151515",
                        "setAsDefault" to true,
                        "nationalId" to "100000000019",
                        "phone" to "+218915000019",
                        "customerEmail" to "customer-login@example.test"
                    )
                )
        ).andExpect(status().isCreated)

        val createdUser = portalUserRepo.findByUsername("customer-login-user")
        assertThat(createdUser).isNotNull
        createdUser!!.passwordHash = encoder.encode("Password123!")
        portalUserRepo.save(createdUser)

        mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "customer-login@example.test",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.username").value("customer-login-user"))

        mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "100000000019",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_method").value("BANK_APP"))
            .andExpect(jsonPath("$.username").value("customer-login-user"))
    }

    @Test
    fun `customer portal phone login can require linked bank app approval`() {
        val bank = registerBank("bank-login")
        val iban = "LY16161616161616161616"
        val customerRef = "cust-$iban"
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "bank-login-user",
                        "iban" to iban,
                        "customerDisplayName" to "Bank Login Customer",
                        "bankCustomerRef" to customerRef,
                        "setAsDefault" to true,
                        "nationalId" to "100000000020",
                        "phone" to "+218916000020",
                        "customerEmail" to "bank-login@example.test"
                    )
                )
        ).andExpect(status().isCreated)

        val user = portalUserRepo.findByUsername("bank-login-user")
        assertThat(user).isNotNull
        user!!.passwordHash = encoder.encode("Password123!")
        portalUserRepo.save(user)

        val pending = mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "0916000020",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_required").value(true))
            .andExpect(jsonPath("$.mfa_method").value("BANK_APP"))
            .andExpect(jsonPath("$.status_token").isNotEmpty)
            .andExpect(jsonPath("$.default_bank_handle").value("bank-login"))
            .andReturn()
            .response
            .contentAsString

        val pendingJson = objectMapper.readTree(pending)
        val challengeId = pendingJson.requiredText("challenge_id")
        val statusToken = pendingJson.requiredText("status_token")

        mockMvc.perform(
            get("/identity/login-approvals/pending")
                .bankKey(bank.apiKey)
                .queryParam("customerRef", customerRef)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].challenge_id").value(challengeId))
            .andExpect(jsonPath("$.data[0].requested_alias").value("bank-login-user"))

        mockMvc.perform(
            post("/identity/login-approvals/$challengeId/approve")
                .bankKey(bank.apiKey)
                .jsonBody(mapOf("customerRef" to customerRef))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.bank_handle").value("bank-login"))

        mockMvc.perform(get("/auth/login/bank-approval/$challengeId"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))

        mockMvc.perform(
            get("/auth/login/bank-approval/$challengeId")
                .header("X-OpenWave-Login-Status-Token", statusToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.session.sessionToken").isNotEmpty)
            .andExpect(jsonPath("$.session.username").value("bank-login-user"))
    }

    @Test
    fun `customer portal can review recent bank login approval activity for own identity`() {
        val bank = registerBank("cust-approval-hist")
        val iban = "LY36363636363636363636"
        val customerRef = "cust-$iban"
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "customer-approval-user",
                        "iban" to iban,
                        "customerDisplayName" to "Approval History Customer",
                        "bankCustomerRef" to customerRef,
                        "setAsDefault" to true,
                        "nationalId" to "100000000036",
                        "phone" to "+218936000036",
                        "customerEmail" to "approval-history@example.test"
                    )
                )
        ).andExpect(status().isCreated)

        val user = portalUserRepo.findByUsername("customer-approval-user")
        assertThat(user).isNotNull
        user!!.passwordHash = encoder.encode("Password123!")
        portalUserRepo.save(user)

        val pending = mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "0936000036",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_method").value("BANK_APP"))
            .andExpect(jsonPath("$.status_token").isNotEmpty)
            .andReturn()
            .response
            .contentAsString

        val challengeId = objectMapper.readTree(pending).requiredText("challenge_id")

        mockMvc.perform(
            post("/identity/login-approvals/$challengeId/approve")
                .bankKey(bank.apiKey)
                .jsonBody(mapOf("customerRef" to customerRef))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))

        val customerSession = portalTokenService.issue(
            subject = "customer-approval-user",
            role = "CUSTOMER",
            bankHandle = null,
            portalRole = PortalRole.CUSTOMER.name
        )

        mockMvc.perform(
            get("/customer/login-approvals")
                .header("X-OpenWave-Portal-Session", customerSession)
                .queryParam("limit", "5")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.total").value(1))
            .andExpect(jsonPath("$.summary.approved").value(1))
            .andExpect(jsonPath("$.items[0].challenge_id").value(challengeId))
            .andExpect(jsonPath("$.items[0].identifier_type").value("PHONE"))
            .andExpect(jsonPath("$.items[0].requested_alias").value("customer-approval-user"))
            .andExpect(jsonPath("$.items[0].approved_bank_handle").value("cust-approval-hist"))
            .andExpect(jsonPath("$.items[0].bank_options[0].bank_handle").value("cust-approval-hist"))

        mockMvc.perform(
            get("/customer/login-approvals/$challengeId")
                .header("X-OpenWave-Portal-Session", customerSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge_id").value(challengeId))
            .andExpect(jsonPath("$.identifier_type").value("PHONE"))
            .andExpect(jsonPath("$.requested_alias").value("customer-approval-user"))
            .andExpect(jsonPath("$.approved_bank_handle").value("cust-approval-hist"))
    }

    @Test
    fun `customer phone login with totp still requires linked bank app approval before session`() {
        val bank = registerBank("totp-bank-login")
        val iban = "LY26262626262626262626"
        val customerRef = "cust-$iban"
        mockMvc.perform(
            post("/identity/claim")
                .bankKey(bank.apiKey)
                .jsonBody(
                    mapOf(
                        "nptHandle" to "totp-bank-login-user",
                        "iban" to iban,
                        "customerDisplayName" to "Totp Bank Login Customer",
                        "bankCustomerRef" to customerRef,
                        "setAsDefault" to true,
                        "nationalId" to "100000000026",
                        "phone" to "+218926000026",
                        "customerEmail" to "totp-bank-login@example.test"
                    )
                )
        ).andExpect(status().isCreated)

        val user = portalUserRepo.findByUsername("totp-bank-login-user")
        assertThat(user).isNotNull
        user!!.passwordHash = encoder.encode("Password123!")
        user.totpSecret = "JBSWY3DPEHPK3PXP"
        user.totpEnabledAt = Instant.now()
        portalUserRepo.save(user)

        val pending = mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "0926000026",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_required").value(true))
            .andExpect(jsonPath("$.mfa_method").value("TOTP"))
            .andReturn()
            .response
            .contentAsString

        val totpChallengeId = objectMapper.readTree(pending).requiredText("challenge_id")

        val bankApprovalPending = mockMvc.perform(
            post("/auth/login/totp/verify")
                .jsonBody(
                    mapOf(
                        "challengeId" to totpChallengeId,
                        "code" to totpCode("JBSWY3DPEHPK3PXP")
                    )
                )
        ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mfa_required").value(true))
            .andExpect(jsonPath("$.mfa_method").value("BANK_APP"))
            .andExpect(jsonPath("$.status_token").isNotEmpty)
            .andExpect(jsonPath("$.username").value("totp-bank-login-user"))
            .andExpect(jsonPath("$.default_bank_handle").value("totp-bank-login"))
            .andReturn()
            .response
            .contentAsString

        val bankApprovalPendingJson = objectMapper.readTree(bankApprovalPending)
        val bankChallengeId = bankApprovalPendingJson.requiredText("challenge_id")
        val bankStatusToken = bankApprovalPendingJson.requiredText("status_token")

        mockMvc.perform(
            get("/auth/login/bank-approval/$bankChallengeId")
                .header("X-OpenWave-Login-Status-Token", bankStatusToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.session").doesNotExist())

        mockMvc.perform(
            post("/identity/login-approvals/$bankChallengeId/approve")
                .bankKey(bank.apiKey)
                .jsonBody(mapOf("customerRef" to customerRef))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))

        mockMvc.perform(
            get("/auth/login/bank-approval/$bankChallengeId")
                .header("X-OpenWave-Login-Status-Token", bankStatusToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.session.sessionToken").isNotEmpty)
            .andExpect(jsonPath("$.session.username").value("totp-bank-login-user"))
    }

    @Test
    fun `login returns role mismatch when credentials are valid for another portal lane`() {
        portalUserRepo.save(
            PortalUserEntity(
                username = "bank-ops-user",
                passwordHash = encoder.encode("Password123!"),
                role = PortalRole.BANK_ADMIN,
                bankHandle = "ops-bank",
                displayName = "Ops User",
                email = "ops-user@example.test"
            )
        )

        mockMvc.perform(
            post("/auth/login")
                .jsonBody(
                    mapOf(
                        "username" to "bank-ops-user",
                        "password" to "Password123!",
                        "role" to "CUSTOMER"
                    )
                )
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"))
            .andExpect(jsonPath("$.expectedRole").value("BANK"))
            .andExpect(jsonPath("$.portalRole").value("BANK_ADMIN"))
    }

    @Test
    fun `portal user can update own recovery email and display name`() {
        val user = portalUserRepo.save(
            PortalUserEntity(
                username = "self-edit-user",
                passwordHash = encoder.encode("Password123!"),
                role = PortalRole.CUSTOMER,
                displayName = "Original Name",
                email = null
            )
        )
        val portalSession = portalTokenService.issue(user.username, "CUSTOMER", null, PortalRole.CUSTOMER.name)

        mockMvc.perform(
            patch("/auth/profile")
                .header("X-OpenWave-Portal-Session", portalSession)
                .jsonBody(
                    mapOf(
                        "displayName" to "Updated Name",
                        "email" to "updated@example.test"
                    )
                )
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("Updated Name"))
            .andExpect(jsonPath("$.email").value("updated@example.test"))

        val refreshed = portalUserRepo.findByUsername("self-edit-user")
        assertThat(refreshed?.displayName).isEqualTo("Updated Name")
        assertThat(refreshed?.email).isEqualTo("updated@example.test")
    }

    @Test
    fun `customer profile marks strong factor setup as required until passkey or totp exists`() {
        val user = portalUserRepo.save(
            PortalUserEntity(
                username = "fresh-customer",
                passwordHash = encoder.encode("Password123!"),
                role = PortalRole.CUSTOMER,
                displayName = "Fresh Customer",
                email = "fresh-customer@example.test"
            )
        )
        val portalSession = portalTokenService.issue(user.username, "CUSTOMER", null, PortalRole.CUSTOMER.name)

        mockMvc.perform(
            get("/auth/profile")
                .header("X-OpenWave-Portal-Session", portalSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.passkeyCount").value(0))
            .andExpect(jsonPath("$.totpEnabled").value(false))
            .andExpect(jsonPath("$.securitySetupRequired").value(true))
            .andExpect(jsonPath("$.securitySetupReason").isNotEmpty)
            .andExpect(jsonPath("$.recommendedSecurityStep").value("ADD_PASSKEY_OR_TOTP"))

        user.totpSecret = "JBSWY3DPEHPK3PXP"
        user.totpEnabledAt = Instant.now()
        portalUserRepo.save(user)

        mockMvc.perform(
            get("/auth/profile")
                .header("X-OpenWave-Portal-Session", portalSession)
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.totpEnabled").value(true))
            .andExpect(jsonPath("$.securitySetupRequired").value(false))
            .andExpect(jsonPath("$.securitySetupReason").doesNotExist())
            .andExpect(jsonPath("$.recommendedSecurityStep").doesNotExist())
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
        customerEmail: String? = "$handle@example.test"
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

    private fun totpCode(secret: String, now: Instant = Instant.now()): String {
        val secretBytes = decodeBase32(secret)
        val timeStep = now.epochSecond / 30
        val buffer = ByteBuffer.allocate(8).putLong(timeStep)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(buffer.array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = value.trim().uppercase().replace("=", "")
        val output = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        cleaned.forEach { char ->
            val digit = alphabet.indexOf(char)
            require(digit >= 0) { "Invalid Base32 secret" }
            buffer = (buffer shl 5) or digit
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output[index++] = (buffer shr (bitsLeft - 8)).toByte()
                bitsLeft -= 8
            }
        }
        return if (index == output.size) output else output.copyOf(index)
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bankKey(apiKey: String) =
        header("X-OpenWave-Bank-Key", apiKey)

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.jsonBody(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private data class BankCredentials(val handle: String, val apiKey: String)
}

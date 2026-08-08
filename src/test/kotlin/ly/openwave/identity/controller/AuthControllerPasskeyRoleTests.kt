package ly.openwave.identity.controller

import com.fasterxml.jackson.databind.ObjectMapper
import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.security.PortalTokenService
import ly.openwave.identity.service.BankService
import ly.openwave.identity.service.PortalBankLoginService
import ly.openwave.identity.service.PortalSecurityService
import ly.openwave.identity.service.PortalTotpService
import ly.openwave.identity.service.PortalUserService
import ly.openwave.identity.service.PortalWebAuthnAuthenticateFinishRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AuthControllerPasskeyRoleTests {
    private val portalUserService = mock(PortalUserService::class.java)
    private val portalSecurityService = mock(PortalSecurityService::class.java)
    private val portalTokenService = mock(PortalTokenService::class.java)
    private val customer = PortalUserEntity(
        username = "npt-customer",
        passwordHash = "unused",
        role = PortalRole.CUSTOMER,
        displayName = "NPT Customer"
    )

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val controller = AuthController(
            props = mock(RegistryProperties::class.java),
            bankService = mock(BankService::class.java),
            portalUserService = portalUserService,
            portalSecurityService = portalSecurityService,
            portalTotpService = mock(PortalTotpService::class.java),
            portalTokenService = portalTokenService,
            portalBankLoginService = mock(PortalBankLoginService::class.java),
            identityRepository = mock(IdentityRepository::class.java)
        )
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build()
        `when`(
            portalSecurityService.finishAuthentication(
                any(PortalWebAuthnAuthenticateFinishRequest::class.java)
                    ?: PortalWebAuthnAuthenticateFinishRequest("unused", "unused")
            )
        ).thenReturn(customer)
        `when`(portalUserService.recordSuccessfulLogin(customer)).thenReturn(customer)
        `when`(portalTokenService.issue(customer.username, "CUSTOMER", null, customer.role.name)).thenReturn("customer-session")
    }

    @Test
    fun `passkey authentication issues a session when requested role matches the authenticated role`() {
        authenticate("""{"challenge":"challenge","credential":"credential","role":"customer"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.sessionToken").value("customer-session"))

        verify(portalTokenService).issue(customer.username, "CUSTOMER", null, customer.role.name)
    }

    @Test
    fun `passkey authentication rejects a requested role that differs from the authenticated role without issuing a session`() {
        authenticate("""{"challenge":"challenge","credential":"credential","role":"BANK"}""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"))
            .andExpect(jsonPath("$.expectedRole").value("CUSTOMER"))
            .andExpect(jsonPath("$.portalRole").value("CUSTOMER"))
            .andExpect(jsonPath("$.username").value(customer.username))
            .andExpect(jsonPath("$.sessionToken").doesNotExist())

        verify(portalUserService, never()).recordSuccessfulLogin(customer)
        verifyNoInteractions(portalTokenService)
    }

    @Test
    fun `passkey authentication remains compatible when role is omitted`() {
        authenticate("""{"challenge":"challenge","credential":"credential"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessionToken").value("customer-session"))
    }

    private fun authenticate(body: String) =
        mockMvc.perform(
            post("/auth/passkey/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
}

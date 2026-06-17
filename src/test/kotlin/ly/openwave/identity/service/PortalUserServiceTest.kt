package ly.openwave.identity.service

import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.repository.PortalUserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class PortalUserServiceTest {

    @Mock
    private lateinit var portalUserRepo: PortalUserRepository

    @Mock
    private lateinit var bankRepo: BankRepository

    @Mock
    private lateinit var identityRepo: IdentityRepository

    @Mock
    private lateinit var notificationService: PortalCredentialNotificationService

    private lateinit var service: PortalUserService

    private val encoder = BCryptPasswordEncoder()

    @BeforeEach
    fun setUp() {
        service = PortalUserService(portalUserRepo, bankRepo, identityRepo, notificationService)
        `when`(portalUserRepo.save(any(PortalUserEntity::class.java))).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `reset password reports no channel and one-time display fallback`() {
        val oldPassword = "old-password"
        val user = portalUser(email = null, passwordHash = encoder.encode(oldPassword))
        `when`(portalUserRepo.findById(user.id)).thenReturn(Optional.of(user))

        val result = service.resetPassword(user.id, callerAdmin = true, callerBankHandle = null)

        assertNotNull(result.temporaryPassword)
        assertNotEquals(oldPassword, result.temporaryPassword)
        assertTrue(encoder.matches(result.temporaryPassword, result.user.passwordHash))
        assertFalse(encoder.matches(oldPassword, result.user.passwordHash))
        assertEquals(CredentialResetNotificationStatus.NO_CHANNEL, result.notification.status)
        assertEquals(null, result.notification.channel)
        assertEquals(CredentialResetFallback.ONE_TIME_DISPLAY, result.notification.fallback)
        assertFalse(result.notification.message.contains(result.temporaryPassword))
    }

    @Test
    fun `reset password reports delivery failure when user has email and provider does not send`() {
        val user = portalUser(email = "operator@example.com", passwordHash = encoder.encode("old-password"))
        `when`(portalUserRepo.findById(user.id)).thenReturn(Optional.of(user))
        `when`(notificationService.sendCredentialEmail(anyString(), anyString(), anyString(), anyString())).thenReturn(false)

        val result = service.resetPassword(user.id, callerAdmin = true, callerBankHandle = null)

        assertTrue(encoder.matches(result.temporaryPassword, result.user.passwordHash))
        assertEquals(CredentialResetNotificationStatus.DELIVERY_FAILED, result.notification.status)
        assertEquals(CredentialResetNotificationChannel.EMAIL, result.notification.channel)
        assertEquals(CredentialResetFallback.ONE_TIME_DISPLAY, result.notification.fallback)
        assertTrue(result.notification.message.contains("delivery", ignoreCase = true))
        assertFalse(result.notification.message.contains(result.temporaryPassword))
    }

    @Test
    fun `reset password reports sent when email delivery succeeds`() {
        val user = portalUser(email = "operator@example.com", passwordHash = encoder.encode("old-password"))
        `when`(portalUserRepo.findById(user.id)).thenReturn(Optional.of(user))
        `when`(notificationService.sendCredentialEmail(anyString(), anyString(), anyString(), anyString())).thenReturn(true)

        val result = service.resetPassword(user.id, callerAdmin = true, callerBankHandle = null)

        assertTrue(encoder.matches(result.temporaryPassword, result.user.passwordHash))
        assertEquals(CredentialResetNotificationStatus.SENT, result.notification.status)
        assertEquals(CredentialResetNotificationChannel.EMAIL, result.notification.channel)
        assertEquals(CredentialResetFallback.ONE_TIME_DISPLAY, result.notification.fallback)
        assertTrue(result.notification.message.contains("sent", ignoreCase = true))
        assertFalse(result.notification.message.contains(result.temporaryPassword))
    }

    private fun portalUser(email: String?, passwordHash: String): PortalUserEntity =
        PortalUserEntity(
            id = 7,
            username = "portal-user",
            passwordHash = passwordHash,
            role = PortalRole.REGISTRY_OPERATOR,
            bankHandle = null,
            displayName = "Portal User",
            email = email
        )
}

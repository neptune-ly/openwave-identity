package ly.openwave.identity.service

import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.repository.BankRepository
import ly.openwave.identity.repository.PortalUserRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant

data class PortalLoginResult(val user: PortalUserEntity)
data class PortalUserCreateResult(val user: PortalUserEntity, val temporaryPassword: String)
data class PortalPasswordResetResult(
    val user: PortalUserEntity,
    val temporaryPassword: String,
    val notification: CredentialResetNotification
)

data class CredentialResetNotification(
    val status: CredentialResetNotificationStatus,
    val channel: CredentialResetNotificationChannel?,
    val fallback: CredentialResetFallback,
    val message: String
)

enum class CredentialResetNotificationStatus {
    NO_CHANNEL,
    PROVIDER_NOT_CONFIGURED
}

enum class CredentialResetNotificationChannel {
    EMAIL
}

enum class CredentialResetFallback {
    ONE_TIME_DISPLAY
}

@Service
class PortalUserService(
    private val portalUserRepo: PortalUserRepository,
    private val bankRepo: BankRepository
) {
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()

    fun resolveLogin(username: String, password: String): PortalLoginResult? {
        val user = portalUserRepo.findByUsername(username) ?: return null
        if (!user.active || !encoder.matches(password, user.passwordHash)) return null
        user.lastLoginAt = Instant.now()
        user.updatedAt = Instant.now()
        portalUserRepo.save(user)
        return PortalLoginResult(user)
    }

    fun listUsers(callerAdmin: Boolean, callerBankHandle: String?): List<PortalUserEntity> =
        if (callerAdmin) portalUserRepo.findAll().sortedBy { it.username }
        else portalUserRepo.findAllByBankHandle(callerBankHandle ?: "").sortedBy { it.username }

    @Transactional
    fun createUser(
        username: String,
        role: PortalRole,
        bankHandle: String?,
        displayName: String,
        email: String?,
        callerAdmin: Boolean,
        callerBankHandle: String?
    ): PortalUserCreateResult {
        if (portalUserRepo.existsByUsername(username)) throw IllegalArgumentException("Username already exists")
        validateRoleScope(role, bankHandle, callerAdmin, callerBankHandle)
        val temporaryPassword = generatePassword()
        val user = portalUserRepo.save(
            PortalUserEntity(
                username = username.trim(),
                passwordHash = encoder.encode(temporaryPassword),
                role = role,
                bankHandle = bankHandle?.trim()?.ifBlank { null },
                displayName = displayName.trim().ifBlank { username.trim() },
                email = email?.trim()?.ifBlank { null }
            )
        )
        return PortalUserCreateResult(user, temporaryPassword)
    }

    @Transactional
    fun updateUser(
        id: Long,
        role: PortalRole?,
        bankHandle: String?,
        displayName: String?,
        email: String?,
        active: Boolean?,
        callerAdmin: Boolean,
        callerBankHandle: String?
    ): PortalUserEntity {
        val user = portalUserRepo.findById(id).orElseThrow { IllegalArgumentException("Portal user not found") }
        ensureCanManage(user, callerAdmin, callerBankHandle)
        val nextRole = role ?: user.role
        val nextBankHandle = if (callerAdmin) bankHandle?.trim()?.ifBlank { null } ?: user.bankHandle else callerBankHandle
        validateRoleScope(nextRole, nextBankHandle, callerAdmin, callerBankHandle)
        user.role = nextRole
        user.bankHandle = nextBankHandle
        if (displayName != null) user.displayName = displayName.trim().ifBlank { user.username }
        if (email != null) user.email = email.trim().ifBlank { null }
        if (active != null) user.active = active
        user.updatedAt = Instant.now()
        return portalUserRepo.save(user)
    }

    @Transactional
    fun resetPassword(id: Long, callerAdmin: Boolean, callerBankHandle: String?): PortalPasswordResetResult {
        val user = portalUserRepo.findById(id).orElseThrow { IllegalArgumentException("Portal user not found") }
        ensureCanManage(user, callerAdmin, callerBankHandle)
        val temporaryPassword = generatePassword()
        user.passwordHash = encoder.encode(temporaryPassword)
        user.updatedAt = Instant.now()
        val savedUser = portalUserRepo.save(user)
        return PortalPasswordResetResult(savedUser, temporaryPassword, credentialResetNotification(savedUser))
    }

    private fun credentialResetNotification(user: PortalUserEntity): CredentialResetNotification =
        if (user.email.isNullOrBlank()) {
            CredentialResetNotification(
                status = CredentialResetNotificationStatus.NO_CHANNEL,
                channel = null,
                fallback = CredentialResetFallback.ONE_TIME_DISPLAY,
                message = "No credential notification channel is configured for this user. The temporary password is returned for one-time display to the authorized operator."
            )
        } else {
            CredentialResetNotification(
                status = CredentialResetNotificationStatus.PROVIDER_NOT_CONFIGURED,
                channel = CredentialResetNotificationChannel.EMAIL,
                fallback = CredentialResetFallback.ONE_TIME_DISPLAY,
                message = "The user has an email address, but no credential notification provider is configured. The temporary password is returned for one-time display to the authorized operator."
            )
        }

    private fun validateRoleScope(role: PortalRole, bankHandle: String?, callerAdmin: Boolean, callerBankHandle: String?) {
        val registryRole = role.name.startsWith("REGISTRY_")
        if (registryRole && !callerAdmin) throw IllegalArgumentException("Only registry admins can manage registry users")
        if (!registryRole) {
            val effectiveBank = bankHandle ?: callerBankHandle
            if (effectiveBank.isNullOrBlank()) throw IllegalArgumentException("Bank users require a bank handle")
            if (!callerAdmin && effectiveBank != callerBankHandle) throw IllegalArgumentException("Bank admins can only manage their own bank")
            if (!bankRepo.existsByBankHandle(effectiveBank)) throw IllegalArgumentException("Bank handle does not exist")
        }
    }

    private fun ensureCanManage(user: PortalUserEntity, callerAdmin: Boolean, callerBankHandle: String?) {
        if (callerAdmin) return
        if (user.bankHandle == null || user.bankHandle != callerBankHandle) {
            throw IllegalArgumentException("You can only manage users for your own bank")
        }
        if (user.role.name.startsWith("REGISTRY_")) {
            throw IllegalArgumentException("Bank admins cannot manage registry users")
        }
    }

    private fun generatePassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return (1..18).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}

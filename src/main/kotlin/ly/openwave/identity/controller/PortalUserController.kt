package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.security.callerBankHandle
import ly.openwave.identity.service.CredentialResetNotification
import ly.openwave.identity.service.PortalUserService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/portal-users")
class PortalUserController(private val portalUserService: PortalUserService) {

    @GetMapping
    fun list(): PortalUserListResponse =
        PortalUserListResponse(portalUserService.listUsers(isAdmin(), bankHandleOrNull()).map { it.toResponse() })

    @PostMapping
    fun create(@Valid @RequestBody req: CreatePortalUserRequest): PortalUserCreateResponse {
        val result = portalUserService.createUser(
            username = req.username,
            role = PortalRole.valueOf(req.role),
            bankHandle = req.bankHandle,
            displayName = req.displayName ?: req.username,
            email = req.email,
            callerAdmin = isAdmin(),
            callerBankHandle = bankHandleOrNull()
        )
        return PortalUserCreateResponse(result.user.toResponse(), result.temporaryPassword)
    }

    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: UpdatePortalUserRequest): PortalUserResponse =
        portalUserService.updateUser(
            id = id,
            role = req.role?.let { PortalRole.valueOf(it) },
            bankHandle = req.bankHandle,
            displayName = req.displayName,
            email = req.email,
            active = req.active,
            callerAdmin = isAdmin(),
            callerBankHandle = bankHandleOrNull()
        ).toResponse()

    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable id: Long): PortalPasswordResetResponse {
        val result = portalUserService.resetPassword(id, isAdmin(), bankHandleOrNull())
        return PortalPasswordResetResponse(result.user.toResponse(), result.temporaryPassword, result.notification.toResponse())
    }

    private fun isAdmin(): Boolean =
        SecurityContextHolder.getContext().authentication?.authorities?.any { it.authority == "ROLE_ADMIN" } == true

    private fun bankHandleOrNull(): String? =
        runCatching { callerBankHandle() }.getOrNull()
}

data class CreatePortalUserRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val role: String,
    val bankHandle: String? = null,
    val displayName: String? = null,
    val email: String? = null
)

data class UpdatePortalUserRequest(
    val role: String? = null,
    val bankHandle: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val active: Boolean? = null
)

data class PortalUserResponse(
    val id: Long,
    val username: String,
    val role: String,
    val bankHandle: String?,
    val displayName: String,
    val email: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastLoginAt: Instant?
)

data class PortalUserListResponse(val users: List<PortalUserResponse>)
data class PortalUserCreateResponse(val user: PortalUserResponse, val temporaryPassword: String)
data class PortalPasswordResetResponse(
    val user: PortalUserResponse,
    val temporaryPassword: String,
    val notification: CredentialResetNotificationResponse
)

data class CredentialResetNotificationResponse(
    val status: String,
    val channel: String?,
    val fallback: String,
    val message: String
)

fun PortalUserEntity.toResponse() = PortalUserResponse(
    id = id,
    username = username,
    role = role.name,
    bankHandle = bankHandle,
    displayName = displayName,
    email = email,
    active = active,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastLoginAt = lastLoginAt
)

fun CredentialResetNotification.toResponse() = CredentialResetNotificationResponse(
    status = status.name,
    channel = channel?.name,
    fallback = fallback.name,
    message = message
)

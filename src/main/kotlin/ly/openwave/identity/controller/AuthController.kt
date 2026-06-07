package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.security.PortalTokenService
import ly.openwave.identity.service.BankService
import ly.openwave.identity.service.PortalPasswordResetConfirmRequest
import ly.openwave.identity.service.PortalPasswordResetRequest
import ly.openwave.identity.service.PortalSecurityService
import ly.openwave.identity.service.PortalWebAuthnAuthenticateFinishRequest
import ly.openwave.identity.service.PortalWebAuthnRegisterFinishRequest
import ly.openwave.identity.service.PortalUserService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
class AuthController(
    private val props: RegistryProperties,
    private val bankService: BankService,
    private val portalUserService: PortalUserService,
    private val portalSecurityService: PortalSecurityService,
    private val portalTokenService: PortalTokenService
) {
    private val encoder = BCryptPasswordEncoder()

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): LoginResponse {
        val role = req.role.uppercase()
        portalUserService.resolveLogin(req.username, req.password)?.let { result ->
            val user = result.user
            val coarseRole = coarseRoleFor(user.role)
            if (role == coarseRole) {
                return LoginResponse(
                    role = coarseRole,
                    username = user.username,
                    bankHandle = user.bankHandle,
                    portalRole = user.role.name,
                    sessionToken = portalTokenService.issue(user.username, coarseRole, user.bankHandle, user.role.name),
                    expiresIn = 28_800
                )
            }
        }

        if (role == "ADMIN") {
            val configuredPassword = props.adminPassword
            val matches = configuredPassword.isNotBlank() && (
                configuredPassword == req.password || encoder.matches(req.password, configuredPassword)
            )
            if (req.username == props.adminUsername && matches) {
                return LoginResponse(
                    role = "ADMIN",
                    username = req.username,
                    bankHandle = null,
                    portalRole = PortalRole.REGISTRY_ADMIN.name,
                    sessionToken = portalTokenService.issue(req.username, "ADMIN", null, PortalRole.REGISTRY_ADMIN.name),
                    expiresIn = 28_800
                )
            }
        }

        if (role == "BANK") {
            val bank = bankService.resolveByPortalLogin(req.username, req.password)
            if (bank != null) {
                return LoginResponse(
                    role = "BANK",
                    username = req.username,
                    bankHandle = bank.bankHandle,
                    portalRole = PortalRole.BANK_ADMIN.name,
                    sessionToken = portalTokenService.issue(req.username, "BANK", bank.bankHandle, PortalRole.BANK_ADMIN.name),
                    expiresIn = 28_800
                )
            }
        }

        throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
    }

    @PostMapping("/password-reset/request")
    fun requestPasswordReset(
        request: HttpServletRequest,
        @Valid @RequestBody req: PortalPasswordResetRequest
    ): Map<String, Any?> =
        portalSecurityService.requestPasswordReset(req, clientIp(request))

    @PostMapping("/password-reset/confirm")
    fun confirmPasswordReset(@Valid @RequestBody req: PortalPasswordResetConfirmRequest): ResponseEntity<Map<String, String>> =
        try {
            portalSecurityService.confirmPasswordReset(req)
            ResponseEntity.ok(mapOf("message" to "Password reset successfully. You can sign in with your new password."))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid or expired reset link")))
        }

    @PostMapping("/passkey/options/authenticate")
    fun passkeyOptions(request: HttpServletRequest): Map<String, String> =
        mapOf("options" to portalSecurityService.startAuthentication(request))

    @PostMapping("/passkey/authenticate")
    fun passkeyAuthenticate(@Valid @RequestBody req: PortalWebAuthnAuthenticateFinishRequest): ResponseEntity<*> =
        try {
            val user = portalSecurityService.finishAuthentication(req)
            val coarseRole = coarseRoleFor(user.role)
            ResponseEntity.ok(
                LoginResponse(
                    role = coarseRole,
                    username = user.username,
                    bankHandle = user.bankHandle,
                    portalRole = user.role.name,
                    sessionToken = portalTokenService.issue(user.username, coarseRole, user.bankHandle, user.role.name),
                    expiresIn = 28_800
                )
            )
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Passkey authentication failed. Please try again."))
        }

    @PostMapping("/passkey/options/register")
    fun passkeyRegisterOptions(
        request: HttpServletRequest,
        @RequestHeader("X-OpenWave-Portal-Session") session: String
    ): Map<String, String> {
        val portalSession = portalTokenService.verify(session)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        val user = portalSecurityService.findUser(portalSession.subject)
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
        return mapOf("options" to portalSecurityService.startRegistration(user, request))
    }

    @PostMapping("/passkey/register")
    fun passkeyRegister(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @Valid @RequestBody req: PortalWebAuthnRegisterFinishRequest
    ): ResponseEntity<Map<String, String>> =
        try {
            val portalSession = portalTokenService.verify(session)
                ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
            val user = portalSecurityService.findUser(portalSession.subject)
                ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")
            portalSecurityService.finishRegistration(user, req)
            ResponseEntity.ok(mapOf("message" to "Passkey registered"))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Failed to register passkey. Please try again."))
        }

    @GetMapping("/profile")
    fun profile(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any?> {
        val user = currentUser(session)
        val coarseRole = coarseRoleFor(user.role)
        return mapOf(
            "role" to coarseRole,
            "username" to user.username,
            "bankHandle" to user.bankHandle,
            "portalRole" to user.role.name,
            "displayName" to user.displayName,
            "email" to user.email,
            "active" to user.active,
            "lastLoginAt" to user.lastLoginAt,
            "createdAt" to user.createdAt,
            "passkeyCount" to portalSecurityService.passkeyCount(user)
        )
    }

    @GetMapping("/passkeys")
    fun listPasskeys(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any> =
        mapOf("passkeys" to portalSecurityService.listPasskeys(currentUser(session)))

    @DeleteMapping("/passkeys/{id}")
    fun deletePasskey(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @PathVariable id: Long
    ): Map<String, String> {
        portalSecurityService.deletePasskey(currentUser(session), id)
        return mapOf("message" to "Passkey removed")
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr

    private fun currentUser(session: String) =
        portalTokenService.verify(session)
            ?.let { portalSecurityService.findUser(it.subject) }
            ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session")

    private fun coarseRoleFor(role: PortalRole): String =
        when {
            role.name.startsWith("REGISTRY_") -> "ADMIN"
            role == PortalRole.CUSTOMER -> "CUSTOMER"
            else -> "BANK"
        }
}

data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val role: String = "ADMIN"
)

data class LoginResponse(
    val role: String,
    val username: String,
    val bankHandle: String?,
    val portalRole: String,
    val sessionToken: String,
    val expiresIn: Long
)

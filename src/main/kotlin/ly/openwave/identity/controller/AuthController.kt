package ly.openwave.identity.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.config.RegistryProperties
import ly.openwave.identity.entity.PortalRole
import ly.openwave.identity.repository.IdentityRepository
import ly.openwave.identity.service.PortalBankLoginService
import ly.openwave.identity.security.PortalTokenService
import ly.openwave.identity.service.BankService
import ly.openwave.identity.service.PortalPasswordResetConfirmRequest
import ly.openwave.identity.service.PortalPasswordResetRequest
import ly.openwave.identity.service.PortalSecurityService
import ly.openwave.identity.service.PortalTotpService
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
    private val portalTotpService: PortalTotpService,
    private val portalTokenService: PortalTokenService,
    private val portalBankLoginService: PortalBankLoginService,
    private val identityRepository: IdentityRepository
) {
    private val encoder = BCryptPasswordEncoder()

    @PostMapping("/login")
    fun login(request: HttpServletRequest, @Valid @RequestBody req: LoginRequest): ResponseEntity<Any> {
        val role = req.role.uppercase()
        portalUserService.resolveLogin(req.username, req.password, role)?.let { result ->
            val user = result.user
            val coarseRole = coarseRoleFor(user.role)
            if (role != coarseRole) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf(
                        "code" to "ROLE_MISMATCH",
                        "message" to "This account belongs to a different portal lane.",
                        "expectedRole" to coarseRole,
                        "portalRole" to user.role.name,
                        "username" to user.username
                    )
                )
            }
            if (!user.totpSecret.isNullOrBlank()) {
                val requiresBankApproval = coarseRole == "CUSTOMER" && result.matchedIdentifierType in setOf("PHONE", "NATIONAL_ID")
                val challenge = portalTotpService.createLoginChallenge(
                    user = user,
                    request = request,
                    requiresBankApproval = requiresBankApproval,
                    identifierType = result.matchedIdentifierType.takeIf { requiresBankApproval },
                    identifierValue = req.username.takeIf { requiresBankApproval }
                )
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    mapOf(
                        "mfa_required" to true,
                        "mfa_method" to challenge.method,
                        "challenge_id" to challenge.challengeId,
                        "expires_in" to challenge.expiresIn,
                        "role" to coarseRole,
                        "username" to user.username,
                        "bankHandle" to user.bankHandle,
                        "portalRole" to user.role.name
                    )
                )
            }
            if (coarseRole == "CUSTOMER" && result.matchedIdentifierType in setOf("PHONE", "NATIONAL_ID")) {
                val identity = identityRepository.findByNptHandle(user.username)
                    ?: throw org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This customer account is not linked to an OpenWave Identity profile."
                    )
                val challenge = portalBankLoginService.createChallenge(
                    user = user,
                    identity = identity,
                    identifierType = result.matchedIdentifierType,
                    rawIdentifier = req.username,
                    request = request
                )
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    mapOf(
                        "mfa_required" to true,
                        "mfa_method" to "BANK_APP",
                        "challenge_id" to challenge.challengeId,
                        "status_token" to challenge.statusToken,
                        "expires_in" to challenge.expiresIn,
                        "identifier_type" to challenge.identifierType,
                        "identifier_hint" to challenge.identifierHint,
                        "default_bank_handle" to challenge.defaultBankHandle,
                        "banks" to challenge.banks,
                        "role" to coarseRole,
                        "username" to user.username,
                        "portalRole" to user.role.name,
                        "message" to "Approve this sign-in from one of your linked bank apps."
                    )
                )
            }
            return ResponseEntity.ok(sessionLoginResponse(user, coarseRole))
        }

        if (role == "ADMIN") {
            val configuredPassword = props.adminPassword
            val matches = configuredPassword.isNotBlank() && (
                configuredPassword == req.password || encoder.matches(req.password, configuredPassword)
            )
            if (req.username == props.adminUsername && matches) {
                return ResponseEntity.ok(
                    LoginResponse(
                        role = "ADMIN",
                        username = req.username,
                        bankHandle = null,
                        portalRole = PortalRole.REGISTRY_ADMIN.name,
                        sessionToken = portalTokenService.issue(req.username, "ADMIN", null, PortalRole.REGISTRY_ADMIN.name),
                        expiresIn = 28_800
                    )
                )
            }
        }

        if (role == "BANK") {
            val bank = bankService.resolveByPortalLogin(req.username, req.password)
            if (bank != null) {
                return ResponseEntity.ok(
                    LoginResponse(
                        role = "BANK",
                        username = req.username,
                        bankHandle = bank.bankHandle,
                        portalRole = PortalRole.BANK_ADMIN.name,
                        sessionToken = portalTokenService.issue(req.username, "BANK", bank.bankHandle, PortalRole.BANK_ADMIN.name),
                        expiresIn = 28_800
                    )
                )
            }
        }

        throw org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password")
    }

    @PostMapping("/login/totp/verify")
    fun verifyTotpLogin(@Valid @RequestBody req: TotpLoginVerifyRequest): ResponseEntity<Any> =
        try {
            val verification = portalTotpService.verifyLoginChallenge(req.challengeId.trim(), req.code.trim())
            val user = verification.user
            val coarseRole = coarseRoleFor(user.role)
            if (verification.requiresBankApproval && coarseRole == "CUSTOMER") {
                val identity = identityRepository.findByNptHandle(user.username)
                    ?: throw org.springframework.web.server.ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "This customer account is not linked to an OpenWave Identity profile."
                    )
                val identifierType = verification.identifierType
                    ?: throw IllegalArgumentException("Login challenge is missing identifier type")
                val identifierValue = verification.identifierValue
                    ?: throw IllegalArgumentException("Login challenge is missing identifier value")
                val challenge = portalBankLoginService.createChallenge(
                    user = user,
                    identity = identity,
                    identifierType = identifierType,
                    rawIdentifier = identifierValue,
                    request = null
                )
                ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    mapOf(
                        "mfa_required" to true,
                        "mfa_method" to "BANK_APP",
                        "challenge_id" to challenge.challengeId,
                        "status_token" to challenge.statusToken,
                        "expires_in" to challenge.expiresIn,
                        "identifier_type" to challenge.identifierType,
                        "identifier_hint" to challenge.identifierHint,
                        "default_bank_handle" to challenge.defaultBankHandle,
                        "banks" to challenge.banks,
                        "role" to coarseRole,
                        "username" to user.username,
                        "portalRole" to user.role.name,
                        "message" to "Approve this sign-in from one of your linked bank apps."
                    )
                )
            } else {
                ResponseEntity.ok(sessionLoginResponse(user, coarseRole))
            }
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to (e.message ?: "Invalid TOTP code")))
        }

    @GetMapping("/login/bank-approval/{challengeId}")
    fun bankApprovalStatus(
        @PathVariable challengeId: String,
        @RequestHeader("X-OpenWave-Login-Status-Token", required = false) statusToken: String?,
        @RequestParam(name = "status_token", required = false) statusTokenQuery: String?
    ): Map<String, Any?> {
        val status = portalBankLoginService.getStatus(challengeId, statusToken ?: statusTokenQuery)
        return mapOf(
            "challenge_id" to status.challengeId,
            "status" to status.status,
            "expires_in" to status.expiresIn,
            "bank_handle" to status.bankHandle,
            "session" to status.session
        )
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
    fun passkeyAuthenticate(@Valid @RequestBody req: PortalWebAuthnAuthenticateFinishRequest): ResponseEntity<*> {
        return try {
            val user = portalSecurityService.finishAuthentication(req)
            val coarseRole = coarseRoleFor(user.role)
            val requestedRole = req.role?.trim()?.uppercase()
            if (requestedRole != null && requestedRole != coarseRole) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf(
                        "code" to "ROLE_MISMATCH",
                        "message" to "This account belongs to a different portal lane.",
                        "expectedRole" to coarseRole,
                        "portalRole" to user.role.name,
                        "username" to user.username
                    )
                )
            }
            ResponseEntity.ok(sessionLoginResponse(user, coarseRole))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Passkey authentication failed. Please try again."))
        }
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
        val passkeyCount = portalSecurityService.passkeyCount(user)
        val totpEnabled = !user.totpSecret.isNullOrBlank()
        val totpPending = !user.totpPendingSecret.isNullOrBlank()
        val securitySetupRequired = user.role == PortalRole.CUSTOMER && passkeyCount == 0 && !totpEnabled
        val securitySetupReason = when {
            !securitySetupRequired -> null
            totpPending -> "Finish authenticator enrollment or register a passkey before continuing."
            user.lastLoginAt == null -> "Customer portal access needs a strong sign-in factor on first use."
            else -> "Customer portal access still depends only on password recovery."
        }
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
            "passkeyCount" to passkeyCount,
            "totpEnabled" to totpEnabled,
            "totpPending" to totpPending,
            "totpEnabledAt" to user.totpEnabledAt,
            "securitySetupRequired" to securitySetupRequired,
            "securitySetupReason" to securitySetupReason,
            "recommendedSecurityStep" to if (securitySetupRequired) "ADD_PASSKEY_OR_TOTP" else null
        )
    }

    @PatchMapping("/profile")
    fun updateProfile(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @Valid @RequestBody req: UpdateProfileRequest
    ): Map<String, Any?> {
        val updated = portalUserService.updateSelfProfile(currentUser(session), req.displayName, req.email)
        return mapOf(
            "username" to updated.username,
            "displayName" to updated.displayName,
            "email" to updated.email,
            "updatedAt" to updated.updatedAt
        )
    }

    @GetMapping("/totp")
    fun totpStatus(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any?> {
        val status = portalTotpService.status(currentUser(session))
        return mapOf(
            "enabled" to status.enabled,
            "pending" to status.pending,
            "enabledAt" to status.enabledAt
        )
    }

    @PostMapping("/totp/setup")
    fun startTotpSetup(@RequestHeader("X-OpenWave-Portal-Session") session: String): Map<String, Any> {
        val setup = portalTotpService.startEnrollment(currentUser(session))
        return mapOf(
            "secret" to setup.secret,
            "otpauthUri" to setup.otpauthUri,
            "issuer" to setup.issuer,
            "accountName" to setup.accountName
        )
    }

    @PostMapping("/totp/confirm")
    fun confirmTotpSetup(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @Valid @RequestBody req: TotpCodeRequest
    ): ResponseEntity<Map<String, Any?>> =
        try {
            val status = portalTotpService.confirmEnrollment(currentUser(session), req.code)
            ResponseEntity.ok(
                mapOf(
                    "enabled" to status.enabled,
                    "pending" to status.pending,
                    "enabledAt" to status.enabledAt,
                    "message" to "TOTP enabled"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid TOTP code")))
        }

    @PostMapping("/totp/disable")
    fun disableTotp(
        @RequestHeader("X-OpenWave-Portal-Session") session: String,
        @Valid @RequestBody req: TotpCodeRequest
    ): ResponseEntity<Map<String, Any?>> =
        try {
            val status = portalTotpService.disable(currentUser(session), req.code)
            ResponseEntity.ok(
                mapOf(
                    "enabled" to status.enabled,
                    "pending" to status.pending,
                    "enabledAt" to status.enabledAt,
                    "message" to "TOTP disabled"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "Invalid TOTP code")))
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

    private fun sessionLoginResponse(user: ly.openwave.identity.entity.PortalUserEntity, coarseRole: String): LoginResponse =
        LoginResponse(
            role = coarseRole,
            username = portalUserService.recordSuccessfulLogin(user).username,
            bankHandle = user.bankHandle,
            portalRole = user.role.name,
            sessionToken = portalTokenService.issue(user.username, coarseRole, user.bankHandle, user.role.name),
            expiresIn = 28_800
        )
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

data class TotpCodeRequest(
    @field:NotBlank val code: String
)

data class TotpLoginVerifyRequest(
    @field:NotBlank val challengeId: String,
    @field:NotBlank val code: String
)

data class UpdateProfileRequest(
    val displayName: String? = null,
    @field:Email val email: String? = null
)

package ly.openwave.identity.service

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.PublicKeyCredentialType
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.data.ByteArray as WebAuthnByteArray
import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.entity.PortalEmailOtpEntity
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.entity.PortalUserPasskeyEntity
import ly.openwave.identity.entity.PortalWebAuthnChallengeEntity
import ly.openwave.identity.repository.PortalEmailOtpRepository
import ly.openwave.identity.repository.PortalUserPasskeyRepository
import ly.openwave.identity.repository.PortalUserRepository
import ly.openwave.identity.repository.PortalWebAuthnChallengeRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

data class PortalPasswordResetRequest(val usernameOrEmail: String)
data class PortalPasswordResetConfirmRequest(
    val usernameOrEmail: String,
    val resetToken: String? = null,
    val otpCode: String? = null,
    val newPassword: String,
)
data class PortalWebAuthnRegisterFinishRequest(val challenge: String, val credential: String, val friendlyName: String? = null)
data class PortalWebAuthnAuthenticateFinishRequest(val challenge: String, val credential: String)
data class PortalWebAuthnContext(val origin: String, val rpId: String)
data class PortalPasskeySummary(
    val id: Long,
    val friendlyName: String?,
    val rpId: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

@Service
class PortalSecurityService(
    private val portalUserRepo: PortalUserRepository,
    private val otpRepo: PortalEmailOtpRepository,
    private val passkeyRepo: PortalUserPasskeyRepository,
    private val challengeRepo: PortalWebAuthnChallengeRepository,
    private val notificationService: PortalCredentialNotificationService,
    @Value("\${identity.auth.webauthn.rp-name:OpenWave Identity}") private val rpName: String,
    @Value("\${identity.auth.webauthn.allowed-origins:}") private val allowedOrigins: String,
    @Value("\${identity.auth.webauthn.challenge-expiry-minutes:5}") private val challengeExpiryMinutes: Long,
    @Value("\${identity.notification.email.portal-url:https://identity.neptune.ly}") private val portalUrl: String,
) {
    private val encoder = BCryptPasswordEncoder()
    private val random = SecureRandom()
    private val relyingPartyCache = ConcurrentHashMap<String, RelyingParty>()

    @Transactional
    fun issuePasswordSetupLink(user: PortalUserEntity, login: String = user.username, ipAddress: String? = null): Boolean {
        if (!user.active || user.email.isNullOrBlank()) return false
        val resetToken = secureToken()
        otpRepo.save(
            PortalEmailOtpEntity(
                user = user,
                purpose = PURPOSE_PASSWORD_RESET_LINK,
                codeHash = encoder.encode(resetToken),
                expiresAt = Instant.now().plusSeconds(600),
                ipAddress = ipAddress,
            )
        )
        return notificationService.sendPasswordResetLink(user.email, user.displayName, resetLink(login, resetToken))
    }

    @Transactional
    fun requestPasswordReset(request: PortalPasswordResetRequest, ipAddress: String?): Map<String, Any?> {
        val user = findUser(request.usernameOrEmail.trim())
        if (user == null || !user.active || user.email.isNullOrBlank()) return genericResetResponse()
        val resetToken = secureToken()
        otpRepo.save(
            PortalEmailOtpEntity(
                user = user,
                purpose = PURPOSE_PASSWORD_RESET_LINK,
                codeHash = encoder.encode(resetToken),
                expiresAt = Instant.now().plusSeconds(600),
                ipAddress = ipAddress,
            )
        )
        notificationService.sendPasswordResetLink(user.email, user.displayName, resetLink(request.usernameOrEmail.trim(), resetToken))
        return genericResetResponse(maskEmail(user.email))
    }

    @Transactional
    fun confirmPasswordReset(request: PortalPasswordResetConfirmRequest) {
        val user = findUser(request.usernameOrEmail.trim()) ?: throw IllegalArgumentException("Invalid or expired reset link")
        if (!user.active || request.newPassword.length < 10) throw IllegalArgumentException("Invalid or expired reset link")
        val presentedToken = request.resetToken?.trim()?.takeIf { it.isNotBlank() }
            ?: request.otpCode?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Invalid or expired reset link")
        val purpose = if (!request.resetToken.isNullOrBlank()) PURPOSE_PASSWORD_RESET_LINK else PURPOSE_PASSWORD_RESET_LEGACY_CODE
        val otp = otpRepo.findTopByUserAndPurposeOrderByCreatedAtDesc(user, purpose)
            .orElseThrow { IllegalArgumentException("Invalid or expired reset link") }
        if (!otp.isValid() || otp.attempts >= 5) throw IllegalArgumentException("Invalid or expired reset link")
        otp.attempts += 1
        if (!encoder.matches(presentedToken, otp.codeHash)) {
            otpRepo.save(otp)
            throw IllegalArgumentException("Invalid or expired reset link")
        }
        otp.usedAt = Instant.now()
        user.passwordHash = encoder.encode(request.newPassword)
        user.updatedAt = Instant.now()
        otpRepo.save(otp)
        portalUserRepo.save(user)
    }

    @Transactional
    fun startRegistration(user: PortalUserEntity, request: HttpServletRequest): String {
        val context = resolveContext(request)
        val options = relyingPartyFor(context).startRegistration(
            StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.username).displayName(user.displayName).id(userHandleForUser(user)).build())
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.PREFERRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build()
                )
                .build()
        )
        challengeRepo.save(
            PortalWebAuthnChallengeEntity(
                user = user,
                purpose = "REGISTER",
                challenge = options.challenge.base64Url,
                requestJson = options.toJson(),
                rpId = context.rpId,
                origin = context.origin,
                expiresAt = Instant.now().plusSeconds(challengeExpiryMinutes * 60),
                ipAddress = clientIp(request),
            )
        )
        return options.toCredentialsCreateJson()
    }

    @Transactional
    fun finishRegistration(user: PortalUserEntity, request: PortalWebAuthnRegisterFinishRequest) {
        val challenge = challengeRepo.findByChallenge(request.challenge).orElseThrow { IllegalArgumentException("Challenge not found") }
        if (!challenge.isValid() || challenge.purpose != "REGISTER" || challenge.user?.id != user.id) throw IllegalArgumentException("Challenge expired or already used")
        val result = relyingPartyFor(PortalWebAuthnContext(challenge.origin!!, challenge.rpId!!)).finishRegistration(
            FinishRegistrationOptions.builder()
                .request(PublicKeyCredentialCreationOptions.fromJson(challenge.requestJson))
                .response(PublicKeyCredential.parseRegistrationResponseJson(request.credential))
                .build()
        )
        passkeyRepo.save(
            PortalUserPasskeyEntity(
                user = user,
                credentialId = result.keyId.id.base64Url,
                publicKey = result.publicKeyCose.base64Url,
                signatureCount = result.signatureCount,
                aaguid = result.aaguid?.toString(),
                attestationType = result.attestationType?.toString(),
                friendlyName = request.friendlyName,
                rpId = challenge.rpId,
                origin = challenge.origin,
            )
        )
        markUsed(challenge)
    }

    @Transactional(readOnly = true)
    fun listPasskeys(user: PortalUserEntity): List<PortalPasskeySummary> =
        passkeyRepo.findAllByUser(user)
            .sortedByDescending { it.createdAt }
            .map {
                PortalPasskeySummary(
                    id = it.id,
                    friendlyName = it.friendlyName,
                    rpId = it.rpId,
                    createdAt = it.createdAt,
                    lastUsedAt = it.lastUsedAt,
                )
            }

    @Transactional(readOnly = true)
    fun passkeyCount(user: PortalUserEntity): Int =
        passkeyRepo.findAllByUser(user).size

    @Transactional
    fun deletePasskey(user: PortalUserEntity, passkeyId: Long) {
        val passkey = passkeyRepo.findById(passkeyId).orElseThrow { IllegalArgumentException("Passkey not found") }
        if (passkey.user.id != user.id) throw IllegalArgumentException("Passkey not found")
        passkeyRepo.delete(passkey)
    }

    @Transactional
    fun startAuthentication(request: HttpServletRequest): String {
        val context = resolveContext(request)
        val assertion = relyingPartyFor(context).startAssertion(
            StartAssertionOptions.builder().userVerification(UserVerificationRequirement.PREFERRED).build()
        )
        challengeRepo.save(
            PortalWebAuthnChallengeEntity(
                purpose = "AUTHENTICATE",
                challenge = assertion.publicKeyCredentialRequestOptions.challenge.base64Url,
                requestJson = assertion.toJson(),
                rpId = context.rpId,
                origin = context.origin,
                expiresAt = Instant.now().plusSeconds(challengeExpiryMinutes * 60),
                ipAddress = clientIp(request),
            )
        )
        return assertion.toCredentialsGetJson()
    }

    @Transactional
    fun finishAuthentication(request: PortalWebAuthnAuthenticateFinishRequest): PortalUserEntity {
        val challenge = challengeRepo.findByChallenge(request.challenge).orElseThrow { IllegalArgumentException("Challenge not found") }
        if (!challenge.isValid() || challenge.purpose != "AUTHENTICATE") throw IllegalArgumentException("Challenge expired or already used")
        val credential = PublicKeyCredential.parseAssertionResponseJson(request.credential)
        val result = relyingPartyFor(PortalWebAuthnContext(challenge.origin!!, challenge.rpId!!)).finishAssertion(
            FinishAssertionOptions.builder()
                .request(AssertionRequest.fromJson(challenge.requestJson))
                .response(credential)
                .build()
        )
        if (!result.isSuccess) throw IllegalArgumentException("Passkey authentication failed")
        val username = result.username ?: throw IllegalArgumentException("Passkey user was not found")
        val user = portalUserRepo.findByUsername(username) ?: throw IllegalArgumentException("Passkey user was not found")
        if (!user.active) throw IllegalArgumentException("Passkey user was not active")
        passkeyRepo.findByCredentialIdWithUser(credential.id.base64Url).ifPresent {
            it.lastUsedAt = Instant.now()
            it.signatureCount = result.signatureCount
            passkeyRepo.save(it)
        }
        markUsed(challenge)
        user.lastLoginAt = Instant.now()
        user.updatedAt = Instant.now()
        return portalUserRepo.save(user)
    }

    fun findUser(login: String): PortalUserEntity? =
        portalUserRepo.findByUsername(login) ?: portalUserRepo.findByEmail(login)

    private fun relyingPartyFor(context: PortalWebAuthnContext): RelyingParty =
        relyingPartyCache.computeIfAbsent("${context.rpId}|${context.origin}") {
            RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(context.rpId).name(rpName).build())
                .credentialRepository(DbCredentialRepository())
                .origins(setOf(context.origin))
                .build()
        }

    private fun resolveContext(request: HttpServletRequest): PortalWebAuthnContext {
        val origin = request.getHeader("Origin") ?: "${request.scheme}://${request.serverName}${if (request.serverPort in listOf(80, 443)) "" else ":${request.serverPort}"}"
        val configured = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (configured.isNotEmpty() && origin !in configured) throw IllegalArgumentException("Origin is not allowed for passkeys")
        return PortalWebAuthnContext(origin, request.serverName)
    }

    private fun userHandleForUser(user: PortalUserEntity): WebAuthnByteArray =
        WebAuthnByteArray(user.id.toString().toByteArray(StandardCharsets.UTF_8))

    private fun markUsed(challenge: PortalWebAuthnChallengeEntity) {
        challenge.isUsed = true
        challenge.usedAt = Instant.now()
        challengeRepo.save(challenge)
    }

    private fun genericResetResponse(maskedEmail: String? = null): Map<String, Any?> =
        mapOf("message" to "If the account exists and has email configured, a secure reset link has been sent.", "emailMasked" to maskedEmail, "expiresInSeconds" to 600)

    private fun secureToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun resetLink(login: String, token: String): String =
        "${portalUrl.trimEnd('/')}/reset-password?login=${java.net.URLEncoder.encode(login, Charsets.UTF_8)}&token=${java.net.URLEncoder.encode(token, Charsets.UTF_8)}"

    private fun maskEmail(email: String?): String? {
        if (email.isNullOrBlank() || !email.contains("@")) return null
        val parts = email.split("@", limit = 2)
        return "${parts[0].take(2)}***@${parts[1]}"
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr

    private inner class DbCredentialRepository : com.yubico.webauthn.CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> {
            val user = portalUserRepo.findByUsername(username) ?: return emptySet()
            return passkeyRepo.findAllByUser(user)
                .map { PublicKeyCredentialDescriptor.builder().id(WebAuthnByteArray.fromBase64Url(it.credentialId)).type(PublicKeyCredentialType.PUBLIC_KEY).build() }
                .toSet()
        }

        override fun getUserHandleForUsername(username: String): Optional<WebAuthnByteArray> {
            val user = portalUserRepo.findByUsername(username) ?: return Optional.empty()
            return Optional.of(userHandleForUser(user))
        }

        override fun getUsernameForUserHandle(userHandle: WebAuthnByteArray): Optional<String> {
            val id = String(userHandle.bytes, StandardCharsets.UTF_8).toLongOrNull() ?: return Optional.empty()
            val user = portalUserRepo.findById(id).orElse(null) ?: return Optional.empty()
            return Optional.of(user.username)
        }

        override fun lookup(credentialId: WebAuthnByteArray, userHandle: WebAuthnByteArray?): Optional<RegisteredCredential> {
            val passkey = passkeyRepo.findByCredentialIdWithUser(credentialId.base64Url).orElse(null) ?: return Optional.empty()
            return Optional.of(
                RegisteredCredential.builder()
                    .credentialId(WebAuthnByteArray.fromBase64Url(passkey.credentialId))
                    .userHandle(userHandleForUser(passkey.user))
                    .publicKeyCose(WebAuthnByteArray.fromBase64Url(passkey.publicKey))
                    .signatureCount(passkey.signatureCount)
                    .build()
            )
        }

        override fun lookupAll(credentialId: WebAuthnByteArray): Set<RegisteredCredential> =
            lookup(credentialId, null).map { setOf(it) }.orElse(emptySet())
    }

    companion object {
        const val PURPOSE_PASSWORD_RESET_LINK = "PASSWORD_RESET_LINK"
        const val PURPOSE_PASSWORD_RESET_LEGACY_CODE = "PASSWORD_RESET"
    }
}

package ly.openwave.identity.service

import jakarta.servlet.http.HttpServletRequest
import ly.openwave.identity.entity.PortalLoginChallengeEntity
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.repository.PortalLoginChallengeRepository
import ly.openwave.identity.repository.PortalUserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PortalTotpSetupResponse(
    val secret: String,
    val otpauthUri: String,
    val issuer: String,
    val accountName: String
)

data class PortalTotpStatusResponse(
    val enabled: Boolean,
    val pending: Boolean,
    val enabledAt: Instant?
)

data class PortalLoginChallengeResult(
    val challengeId: String,
    val expiresIn: Long,
    val method: String = "TOTP"
)

data class PortalLoginChallengeVerificationResult(
    val user: PortalUserEntity,
    val requiresBankApproval: Boolean,
    val identifierType: String?,
    val identifierValue: String?
)

@Service
class PortalTotpService(
    private val portalUserRepository: PortalUserRepository,
    private val loginChallengeRepository: PortalLoginChallengeRepository
) {
    private val random = SecureRandom()
    private val issuer = "OpenWave Identity"

    fun status(user: PortalUserEntity): PortalTotpStatusResponse =
        PortalTotpStatusResponse(
            enabled = !user.totpSecret.isNullOrBlank(),
            pending = !user.totpPendingSecret.isNullOrBlank(),
            enabledAt = user.totpEnabledAt
        )

    @Transactional
    fun startEnrollment(user: PortalUserEntity): PortalTotpSetupResponse {
        val secret = generateSecret()
        user.totpPendingSecret = secret
        user.updatedAt = Instant.now()
        portalUserRepository.save(user)
        val accountName = user.email?.takeIf { it.isNotBlank() } ?: user.username
        return PortalTotpSetupResponse(
            secret = secret,
            otpauthUri = buildOtpAuthUri(accountName, secret),
            issuer = issuer,
            accountName = accountName
        )
    }

    @Transactional
    fun confirmEnrollment(user: PortalUserEntity, code: String): PortalTotpStatusResponse {
        val secret = user.totpPendingSecret ?: throw IllegalArgumentException("Start TOTP setup first")
        if (!verify(secret, code)) {
            throw IllegalArgumentException("Invalid TOTP code")
        }
        user.totpSecret = secret
        user.totpPendingSecret = null
        user.totpEnabledAt = Instant.now()
        user.updatedAt = Instant.now()
        return status(portalUserRepository.save(user))
    }

    @Transactional
    fun disable(user: PortalUserEntity, code: String): PortalTotpStatusResponse {
        val secret = user.totpSecret ?: throw IllegalArgumentException("TOTP is not enabled")
        if (!verify(secret, code)) {
            throw IllegalArgumentException("Invalid TOTP code")
        }
        user.totpSecret = null
        user.totpPendingSecret = null
        user.totpEnabledAt = null
        user.updatedAt = Instant.now()
        return status(portalUserRepository.save(user))
    }

    @Transactional
    fun createLoginChallenge(
        user: PortalUserEntity,
        request: HttpServletRequest?,
        requiresBankApproval: Boolean = false,
        identifierType: String? = null,
        identifierValue: String? = null
    ): PortalLoginChallengeResult {
        val challengeId = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = Instant.now().plusSeconds(300)
        val ipAddress = request?.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request?.remoteAddr?.trim()?.takeIf { it.isNotBlank() }
        loginChallengeRepository.save(
            PortalLoginChallengeEntity(
                id = challengeId,
                user = user,
                requiresBankApproval = requiresBankApproval,
                identifierType = identifierType,
                identifierValue = identifierValue,
                expiresAt = expiresAt,
                ipAddress = ipAddress
            )
        )
        return PortalLoginChallengeResult(challengeId = challengeId, expiresIn = 300)
    }

    @Transactional
    fun verifyLoginChallenge(challengeId: String, code: String): PortalLoginChallengeVerificationResult {
        val challenge = loginChallengeRepository.findById(challengeId)
            .orElseThrow { IllegalArgumentException("Login challenge expired") }
        if (!challenge.isValid()) {
            throw IllegalArgumentException("Login challenge expired")
        }
        val user = challenge.user
        val secret = user.totpSecret ?: throw IllegalArgumentException("TOTP is not enabled")
        if (!verify(secret, code)) {
            throw IllegalArgumentException("Invalid TOTP code")
        }
        challenge.usedAt = Instant.now()
        loginChallengeRepository.save(challenge)
        return PortalLoginChallengeVerificationResult(
            user = user,
            requiresBankApproval = challenge.requiresBankApproval,
            identifierType = challenge.identifierType,
            identifierValue = challenge.identifierValue
        )
    }

    fun verifyActiveCode(user: PortalUserEntity, code: String): Boolean {
        val secret = user.totpSecret ?: return false
        return verify(secret, code)
    }

    private fun buildOtpAuthUri(accountName: String, secret: String): String =
        "otpauth://totp/${urlEncode(issuer)}:${urlEncode(accountName)}?secret=$secret&issuer=${urlEncode(issuer)}&algorithm=SHA1&digits=6&period=30"

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun generateSecret(): String {
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        return Base32.encode(bytes)
    }

    private fun verify(secret: String, code: String, now: Instant = Instant.now()): Boolean {
        val normalizedCode = code.filter(Char::isDigit)
        if (normalizedCode.length != 6) return false
        val secretBytes = Base32.decode(secret)
        val currentStep = now.epochSecond / 30
        for (offset in -1L..1L) {
            if (totpCode(secretBytes, currentStep + offset) == normalizedCode) return true
        }
        return false
    }

    private fun totpCode(secretBytes: ByteArray, timeStep: Long): String {
        val buffer = ByteBuffer.allocate(8).putLong(timeStep)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(buffer.array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    private object Base32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private val LOOKUP = IntArray(128) { -1 }.also { table ->
            ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
        }

        fun encode(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            val out = StringBuilder((bytes.size * 8 + 4) / 5)
            var buffer = bytes[0].toInt() and 0xff
            var next = 1
            var bitsLeft = 8
            while (bitsLeft > 0 || next < bytes.size) {
                if (bitsLeft < 5) {
                    if (next < bytes.size) {
                        buffer = (buffer shl 8) or (bytes[next++].toInt() and 0xff)
                        bitsLeft += 8
                    } else {
                        val pad = 5 - bitsLeft
                        buffer = buffer shl pad
                        bitsLeft += pad
                    }
                }
                val index = buffer shr (bitsLeft - 5) and 0x1f
                bitsLeft -= 5
                out.append(ALPHABET[index])
            }
            return out.toString()
        }

        fun decode(value: String): ByteArray {
            val cleaned = value.trim().uppercase().replace("=", "")
            if (cleaned.isEmpty()) return byteArrayOf()
            val out = ByteArray(cleaned.length * 5 / 8)
            var buffer = 0
            var bitsLeft = 0
            var index = 0
            cleaned.forEach { c ->
                val digit = if (c.code < LOOKUP.size) LOOKUP[c.code] else -1
                require(digit >= 0) { "Invalid Base32 secret" }
                buffer = (buffer shl 5) or digit
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    out[index++] = (buffer shr (bitsLeft - 8)).toByte()
                    bitsLeft -= 8
                }
            }
            return if (index == out.size) out else out.copyOf(index)
        }
    }
}

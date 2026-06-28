package ly.openwave.identity.security

import ly.openwave.identity.config.RegistryProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PortalSessionPrincipal(
    val subject: String,
    val role: String,
    val bankHandle: String?,
    val portalRole: String?,
    val expiresAt: Long
)

@Service
class PortalTokenService(private val props: RegistryProperties) {

    private val log = LoggerFactory.getLogger(PortalTokenService::class.java)

    // HMAC secret for portal session tokens. Uses the configured admin key when present.
    // When it is blank — which only happens in a misconfigured/local setup, since prod
    // requires REGISTRY_ADMIN_KEY (application.yml has no default, so the app won't start
    // without it) — fall back to a random per-process secret instead of a hardcoded constant,
    // so portal/admin session tokens can never be forged with a value published in this
    // (public) repository. Sessions reset on restart in that degraded mode.
    private val signingSecret: ByteArray = props.adminKey
        .takeIf { it.isNotBlank() }
        ?.toByteArray()
        ?: run {
            log.error(
                "REGISTRY_ADMIN_KEY is not configured; portal session tokens are signed with an " +
                    "ephemeral random secret (sessions reset on restart). Set REGISTRY_ADMIN_KEY."
            )
            ByteArray(32).also { SecureRandom().nextBytes(it) }
        }

    fun issue(subject: String, role: String, bankHandle: String?, portalRole: String? = null, ttlSeconds: Long = 28_800): String {
        val expiresAt = Instant.now().epochSecond + ttlSeconds
        val payload = listOf(subject, role, bankHandle ?: "", portalRole ?: "", expiresAt.toString()).joinToString("|")
        val encodedPayload = b64(payload.toByteArray())
        val signature = sign(encodedPayload)
        return "$encodedPayload.$signature"
    }

    fun verify(token: String?): PortalSessionPrincipal? {
        if (token.isNullOrBlank() || !token.contains(".")) return null
        val encodedPayload = token.substringBefore(".")
        val signature = token.substringAfter(".")
        if (sign(encodedPayload) != signature) return null
        val parts = String(Base64.getUrlDecoder().decode(encodedPayload)).split("|")
        if (parts.size !in setOf(4, 5)) return null
        val expiresAt = parts.last().toLongOrNull() ?: return null
        if (expiresAt <= Instant.now().epochSecond) return null
        return PortalSessionPrincipal(
            subject = parts[0],
            role = parts[1],
            bankHandle = parts[2].takeIf { it.isNotBlank() },
            portalRole = if (parts.size == 5) parts[3].takeIf { it.isNotBlank() } else null,
            expiresAt = expiresAt
        )
    }

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret, "HmacSHA256"))
        return b64(mac.doFinal(value.toByteArray()))
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

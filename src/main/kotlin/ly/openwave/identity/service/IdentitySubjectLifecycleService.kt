package ly.openwave.identity.service

import ly.openwave.identity.repository.OAuthTokenRepository
import ly.openwave.identity.repository.OAuthUserGrantRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Ends mutable-username credentials when an NPT handle changes.
 *
 * OAuth token and grant subjects are immutable audit records. Migrating them
 * to a new payment identity would silently carry third-party consent across a
 * rename; leaving them active would authorize the permanently retired subject.
 * The safe boundary is explicit forced re-authentication and fresh consent.
 * This runs inside [IdentityService.renameHandle]'s transaction, so a failure
 * to revoke credentials rolls the rename and tombstone back together.
 */
@Service
class IdentitySubjectLifecycleService(
    private val oauthTokens: OAuthTokenRepository,
    private val oauthGrants: OAuthUserGrantRepository,
    private val audit: PortalAuditService
) {
    fun revokeForHandleRename(previousHandle: String, newHandle: String) {
        val now = Instant.now()
        val tokenRows = oauthTokens.findAllBySubject(previousHandle)
        val activeTokens = tokenRows.filter { it.revokedAt == null }
        activeTokens.forEach {
            it.revokedAt = now
            it.revokeReason = "identity_handle_renamed"
        }
        if (activeTokens.isNotEmpty()) oauthTokens.saveAll(activeTokens)

        val grantRows = oauthGrants.findAllBySubject(previousHandle)
        val activeGrants = grantRows.filter { it.active }
        activeGrants.forEach {
            it.active = false
            it.revokedAt = now
            it.revokedBy = "identity-handle-rename"
        }
        if (activeGrants.isNotEmpty()) oauthGrants.saveAll(activeGrants)

        audit.record(
            authentication = null,
            action = "IDENTITY_HANDLE_CREDENTIALS_REVOKED",
            entityType = "IDENTITY",
            entityId = previousHandle,
            details = mapOf(
                "new_handle" to newHandle,
                "oauth_tokens" to activeTokens.size,
                "oauth_grants" to activeGrants.size
            )
        )
    }
}

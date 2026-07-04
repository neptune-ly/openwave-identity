package ly.openwave.identity.repository

import ly.openwave.identity.entity.OAuthAuthorizationRequestEntity
import ly.openwave.identity.entity.OAuthClientEntity
import ly.openwave.identity.entity.OAuthMcpAuditEventEntity
import ly.openwave.identity.entity.OAuthSettingEntity
import ly.openwave.identity.entity.OAuthTokenEntity
import ly.openwave.identity.entity.OAuthUserGrantEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

interface OAuthClientRepository : JpaRepository<OAuthClientEntity, Long> {
    fun findByClientId(clientId: String): OAuthClientEntity?
    fun existsByClientId(clientId: String): Boolean
}

interface OAuthTokenRepository : JpaRepository<OAuthTokenEntity, Long> {
    fun findByTokenHash(tokenHash: String): OAuthTokenEntity?
    fun findByRefreshTokenHash(refreshTokenHash: String): OAuthTokenEntity?
    fun findAllByClientId(clientId: String): List<OAuthTokenEntity>
    fun findAllBySubject(subject: String): List<OAuthTokenEntity>
    fun findAllByClientIdAndRevokedAtIsNull(clientId: String): List<OAuthTokenEntity>
}

interface OAuthSettingRepository : JpaRepository<OAuthSettingEntity, String> {
    fun findByKey(key: String): Optional<OAuthSettingEntity>
}

interface OAuthUserGrantRepository : JpaRepository<OAuthUserGrantEntity, Long> {
    fun findAllBySubject(subject: String): List<OAuthUserGrantEntity>
    fun findAllBySubjectAndClientIdAndActiveIsTrue(subject: String, clientId: String): List<OAuthUserGrantEntity>
}

interface OAuthMcpAuditEventRepository : JpaRepository<OAuthMcpAuditEventEntity, Long>

interface OAuthAuthorizationRequestRepository : JpaRepository<OAuthAuthorizationRequestEntity, String> {
    fun findByAuthorizationCodeHash(authorizationCodeHash: String): OAuthAuthorizationRequestEntity?

    @Modifying
    @Query("delete from OAuthAuthorizationRequestEntity r where r.requestExpiresAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: Instant): Int
}

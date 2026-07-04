package ly.openwave.identity.entity

import jakarta.persistence.*
import java.time.Instant

enum class OAuthClientType { CONFIDENTIAL, PUBLIC, AGENT, RESOURCE_SERVER }
enum class OAuthOwnerType { NEPTUNE, MERCHANT, BANK, CUSTOMER }
enum class OAuthEnvironment { SANDBOX, LIVE }
enum class OAuthAuthorizationStatus { PENDING, APPROVED, REJECTED, EXCHANGED, EXPIRED }

@Entity
@Table(name = "oauth_clients")
class OAuthClientEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "client_id", nullable = false, unique = true, length = 80)
    val clientId: String,

    @Column(name = "client_secret_hash", length = 128)
    var clientSecretHash: String? = null,

    @Column(name = "display_name", nullable = false, length = 160)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 40)
    var clientType: OAuthClientType = OAuthClientType.CONFIDENTIAL,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 40)
    var ownerType: OAuthOwnerType = OAuthOwnerType.NEPTUNE,

    @Column(name = "owner_id", length = 120)
    var ownerId: String? = null,

    @Column(name = "owner_handle", length = 120)
    var ownerHandle: String? = null,

    @Column(name = "redirect_uris", nullable = false, columnDefinition = "TEXT")
    var redirectUris: String = "[]",

    @Column(name = "allowed_scopes", nullable = false, columnDefinition = "TEXT")
    var allowedScopes: String = "[]",

    @Column(name = "allowed_environments", nullable = false, length = 80)
    var allowedEnvironments: String = "SANDBOX",

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "mcp_enabled", nullable = false)
    var mcpEnabled: Boolean = false,

    @Column(name = "live_enabled", nullable = false)
    var liveEnabled: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "secret_rotated_at")
    var secretRotatedAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
)

@Entity
@Table(name = "oauth_tokens")
class OAuthTokenEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    val tokenHash: String,

    @Column(name = "refresh_token_hash", unique = true, length = 128)
    var refreshTokenHash: String? = null,

    @Column(name = "client_id", nullable = false, length = 80)
    val clientId: String,

    @Column(name = "subject", nullable = false, length = 160)
    val subject: String,

    @Column(name = "subject_role", length = 40)
    val subjectRole: String? = null,

    @Column(name = "audience", nullable = false, length = 80)
    val audience: String,

    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    val scopes: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 40)
    val ownerType: OAuthOwnerType,

    @Column(name = "owner_id", length = 120)
    val ownerId: String? = null,

    @Column(name = "owner_handle", length = 120)
    val ownerHandle: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 20)
    val environment: OAuthEnvironment,

    @Column(name = "grant_type", nullable = false, length = 40)
    val grantType: String,

    @Column(name = "grant_id")
    val grantId: Long? = null,

    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "refresh_expires_at")
    val refreshExpiresAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "revoke_reason", length = 160)
    var revokeReason: String? = null
)

@Entity
@Table(name = "oauth_settings")
class OAuthSettingEntity(
    @Id
    @Column(name = "setting_key", nullable = false, length = 80)
    val key: String,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "updated_by", length = 160)
    var updatedBy: String? = null,

    @Column(name = "note", length = 255)
    var note: String? = null
)

@Entity
@Table(name = "oauth_user_grants")
class OAuthUserGrantEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "subject", nullable = false, length = 160)
    val subject: String,

    @Column(name = "client_id", nullable = false, length = 80)
    val clientId: String,

    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    val scopes: String,

    @Column(name = "audience", nullable = false, length = 80)
    var audience: String = "astro",

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 20)
    var environment: OAuthEnvironment = OAuthEnvironment.SANDBOX,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 40)
    val ownerType: OAuthOwnerType,

    @Column(name = "owner_id", length = 120)
    val ownerId: String? = null,

    @Column(name = "owner_handle", length = 120)
    val ownerHandle: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "approved_by", length = 160)
    val approvedBy: String? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "revoked_by", length = 160)
    var revokedBy: String? = null
)

@Entity
@Table(name = "oauth_authorization_requests")
class OAuthAuthorizationRequestEntity(
    @Id
    @Column(name = "request_id", nullable = false, length = 80)
    val requestId: String,

    @Column(name = "client_id", nullable = false, length = 80)
    val clientId: String,

    @Column(name = "subject", length = 160)
    var subject: String? = null,

    @Column(name = "subject_role", length = 40)
    var subjectRole: String? = null,

    @Column(name = "redirect_uri", nullable = false, length = 500)
    val redirectUri: String,

    @Column(name = "scopes", nullable = false, columnDefinition = "TEXT")
    val scopes: String,

    @Column(name = "audience", nullable = false, length = 80)
    val audience: String = "astro",

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 20)
    val environment: OAuthEnvironment = OAuthEnvironment.SANDBOX,

    @Column(name = "state", length = 500)
    val state: String? = null,

    @Column(name = "code_challenge", nullable = false, length = 160)
    val codeChallenge: String,

    @Column(name = "code_challenge_method", nullable = false, length = 20)
    val codeChallengeMethod: String = "S256",

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: OAuthAuthorizationStatus = OAuthAuthorizationStatus.PENDING,

    @Column(name = "authorization_code_hash", unique = true, length = 128)
    var authorizationCodeHash: String? = null,

    @Column(name = "request_expires_at", nullable = false)
    val requestExpiresAt: Instant,

    @Column(name = "code_expires_at")
    var codeExpiresAt: Instant? = null,

    @Column(name = "approved_at")
    var approvedAt: Instant? = null,

    @Column(name = "rejected_at")
    var rejectedAt: Instant? = null,

    @Column(name = "exchanged_at")
    var exchangedAt: Instant? = null,

    @Column(name = "grant_id")
    var grantId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "oauth_mcp_audit_events")
class OAuthMcpAuditEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "client_id", length = 80)
    val clientId: String? = null,

    @Column(name = "subject", length = 160)
    val subject: String? = null,

    @Column(name = "owner_type", length = 40)
    val ownerType: String? = null,

    @Column(name = "owner_id", length = 120)
    val ownerId: String? = null,

    @Column(name = "owner_handle", length = 120)
    val ownerHandle: String? = null,

    @Column(name = "scopes", columnDefinition = "TEXT")
    val scopes: String? = null,

    @Column(name = "tool_name", nullable = false, length = 120)
    val toolName: String,

    @Column(name = "result_status", nullable = false, length = 40)
    val resultStatus: String,

    @Column(name = "metadata", columnDefinition = "TEXT")
    val metadata: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

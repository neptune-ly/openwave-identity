package ly.openwave.identity.entity

import jakarta.persistence.*
import org.hibernate.annotations.ColumnTransformer
import java.security.MessageDigest
import java.time.Instant

@Entity
@Table(name = "registered_banks")
class BankEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "bank_handle", nullable = false, unique = true, length = 20)
    val bankHandle: String,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Column(name = "country", nullable = false, length = 2)
    val country: String,

    @Column(name = "core_url", nullable = false, length = 500)
    var coreUrl: String,

    @Column(name = "contact_email", nullable = false, length = 255)
    var contactEmail: String,

    @Column(name = "logo_url", length = 512)
    var logoUrl: String? = null,

    @Column(name = "brand_color", length = 32)
    var brandColor: String? = null,

    @Column(name = "support_email", length = 255)
    var supportEmail: String? = null,

    @Column(name = "website", length = 512)
    var website: String? = null,

    @Column(name = "api_key_hash", nullable = false, length = 64)
    val apiKeyHash: String,

    @Column(name = "portal_username", unique = true, length = 80)
    var portalUsername: String? = null,

    @Column(name = "portal_password_hash", length = 255)
    var portalPasswordHash: String? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "registered_at", nullable = false, updatable = false)
    val registeredAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "portal_audit_events")
class PortalAuditEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "actor", nullable = false, length = 160)
    val actor: String,

    @Column(name = "action", nullable = false, length = 80)
    val action: String,

    @Column(name = "entity_type", nullable = false, length = 40)
    val entityType: String,

    @Column(name = "entity_id", nullable = false, length = 120)
    val entityId: String,

    @Column(name = "details", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    val details: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

enum class PortalRole { REGISTRY_ADMIN, REGISTRY_OPERATOR, BANK_ADMIN, BANK_OPERATOR, BANK_VIEWER, CUSTOMER }

@Entity
@Table(name = "portal_users")
class PortalUserEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "username", nullable = false, unique = true, length = 80)
    var username: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var role: PortalRole,

    @Column(name = "bank_handle", length = 20)
    var bankHandle: String? = null,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Column(name = "email", length = 255)
    var email: String? = null,

    @Column(name = "totp_secret", length = 128)
    var totpSecret: String? = null,

    @Column(name = "totp_pending_secret", length = 128)
    var totpPendingSecret: String? = null,

    @Column(name = "totp_enabled_at")
    var totpEnabledAt: Instant? = null,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
)

@Entity
@Table(name = "portal_email_otps")
class PortalEmailOtpEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: PortalUserEntity,

    @Column(name = "purpose", nullable = false, length = 40)
    val purpose: String,

    @Column(name = "code_hash", nullable = false, length = 255)
    var codeHash: String,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "ip_address", length = 45)
    val ipAddress: String? = null
) {
    fun isValid(now: Instant = Instant.now()): Boolean = usedAt == null && now.isBefore(expiresAt)
}

@Entity
@Table(name = "portal_user_passkeys")
class PortalUserPasskeyEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: PortalUserEntity,

    @Column(name = "credential_id", nullable = false, unique = true, length = 512)
    val credentialId: String,

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    val publicKey: String,

    @Column(name = "signature_count", nullable = false)
    var signatureCount: Long = 0,

    @Column(name = "aaguid", length = 64)
    val aaguid: String? = null,

    @Column(name = "attestation_type", length = 50)
    val attestationType: String? = null,

    @Column(name = "friendly_name", length = 120)
    var friendlyName: String? = null,

    @Column(name = "rp_id", length = 255)
    var rpId: String? = null,

    @Column(name = "origin", length = 255)
    var origin: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
)

@Entity
@Table(name = "portal_webauthn_challenges")
class PortalWebAuthnChallengeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    val user: PortalUserEntity? = null,

    @Column(name = "purpose", nullable = false, length = 30)
    val purpose: String,

    @Column(name = "challenge", nullable = false, unique = true, length = 512)
    val challenge: String,

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    val requestJson: String,

    @Column(name = "rp_id", length = 255)
    val rpId: String? = null,

    @Column(name = "origin", length = 255)
    val origin: String? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "is_used", nullable = false)
    var isUsed: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "ip_address", length = 45)
    val ipAddress: String? = null
) {
    fun isValid(now: Instant = Instant.now()): Boolean = !isUsed && now.isBefore(expiresAt)
}

@Entity
@Table(name = "portal_login_challenges")
class PortalLoginChallengeEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    val id: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: PortalUserEntity,

    @Column(name = "purpose", nullable = false, length = 30)
    val purpose: String = "TOTP_LOGIN",

    @Column(name = "requires_bank_approval", nullable = false)
    val requiresBankApproval: Boolean = false,

    @Column(name = "identifier_type", length = 24)
    val identifierType: String? = null,

    @Column(name = "identifier_value", length = 120)
    val identifierValue: String? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "ip_address", length = 45)
    val ipAddress: String? = null
) {
    fun isValid(now: Instant = Instant.now()): Boolean = usedAt == null && now.isBefore(expiresAt)
}

enum class BankLoginChallengeStatus { PENDING, APPROVED, REJECTED }

@Entity
@Table(name = "portal_bank_login_challenges")
class PortalBankLoginChallengeEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    val id: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: PortalUserEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identity_id", nullable = false)
    val identity: IdentityEntity,

    @Column(name = "identifier_type", nullable = false, length = 24)
    val identifierType: String,

    @Column(name = "identifier_hint", nullable = false, length = 120)
    val identifierHint: String,

    @Column(name = "status_token_hash", length = 64)
    val statusTokenHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: BankLoginChallengeStatus = BankLoginChallengeStatus.PENDING,

    @Column(name = "approved_bank_handle", length = 20)
    var approvedBankHandle: String? = null,

    @Column(name = "portal_session_token", length = 2048)
    var portalSessionToken: String? = null,

    @Column(name = "portal_session_expires_at")
    var portalSessionExpiresAt: Instant? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "actioned_at")
    var actionedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "ip_address", length = 45)
    val ipAddress: String? = null
) {
    fun isPending(now: Instant = Instant.now()): Boolean =
        status == BankLoginChallengeStatus.PENDING && now.isBefore(expiresAt)

    fun effectiveStatus(now: Instant = Instant.now()): String =
        if (status == BankLoginChallengeStatus.PENDING && now.isAfter(expiresAt)) "EXPIRED" else status.name

    fun matchesStatusToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        val expected = statusTokenHash ?: return false
        return MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
    }
}

enum class IdentityStatus { ACTIVE, SUSPENDED, DELETED }

@Entity
@Table(name = "npt_identities")
class IdentityEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "npt_handle", nullable = false, unique = true, length = 32)
    val nptHandle: String,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: IdentityStatus = IdentityStatus.ACTIVE,

    @Column(name = "default_bank_handle", length = 20)
    var defaultBankHandle: String? = null,

    @Column(name = "national_id", length = 12, unique = true)
    var nationalId: String? = null,

    @Column(name = "phone", length = 20)
    var phone: String? = null,

    @Column(name = "email", length = 255)
    var email: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "identity", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val linkedAccounts: MutableList<LinkedAccountEntity> = mutableListOf()
)

@Entity
@Table(
    name = "linked_accounts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["identity_id", "iban"])]
)
class LinkedAccountEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "identity_id", nullable = false)
    val identity: IdentityEntity,

    @Column(name = "bank_handle", nullable = false, length = 20)
    val bankHandle: String,

    @Column(name = "iban", nullable = false, length = 34)
    var iban: String,

    @Column(name = "bank_customer_ref", nullable = false, length = 100)
    val bankCustomerRef: String,

    @Column(name = "display_name", nullable = true, length = 100)
    var displayName: String? = null,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "LYD",

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(name = "linked_at", nullable = false, updatable = false)
    val linkedAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

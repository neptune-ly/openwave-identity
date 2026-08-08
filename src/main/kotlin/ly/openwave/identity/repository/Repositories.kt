package ly.openwave.identity.repository

import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.entity.PortalEmailOtpEntity
import ly.openwave.identity.entity.PortalAuditEventEntity
import ly.openwave.identity.entity.PortalBankLoginChallengeEntity
import ly.openwave.identity.entity.PortalUserEntity
import ly.openwave.identity.entity.PortalLoginChallengeEntity
import ly.openwave.identity.entity.BankLoginChallengeStatus
import ly.openwave.identity.entity.PortalUserPasskeyEntity
import ly.openwave.identity.entity.PortalWebAuthnChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
interface BankRepository : JpaRepository<BankEntity, Long> {
    fun findByBankHandle(handle: String): BankEntity?
    fun findByPortalUsername(username: String): BankEntity?
    fun existsByBankHandle(handle: String): Boolean
    fun findByApiKeyHash(hash: String): BankEntity?
    fun countByActiveTrue(): Long
    fun findAllByActiveTrue(): List<BankEntity>
    fun findAllByCountryAndActiveTrue(country: String): List<BankEntity>
}

@Repository
interface PortalAuditEventRepository : JpaRepository<PortalAuditEventEntity, Long> {
    fun findAllByOrderByCreatedAtDesc(): List<PortalAuditEventEntity>
    fun findAllByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType: String, entityId: String): List<PortalAuditEventEntity>
}

@Repository
interface PortalUserRepository : JpaRepository<PortalUserEntity, Long> {
    fun findByUsername(username: String): PortalUserEntity?
    fun findByEmail(email: String): PortalUserEntity?
    fun existsByUsername(username: String): Boolean
    fun countByActiveTrue(): Long
    fun countByRole(role: ly.openwave.identity.entity.PortalRole): Long
    fun countByRoleAndActiveTrue(role: ly.openwave.identity.entity.PortalRole): Long
    fun findAllByBankHandle(bankHandle: String): List<PortalUserEntity>
}

@Repository
interface PortalLoginChallengeRepository : JpaRepository<PortalLoginChallengeEntity, String> {
    fun findTopByUserOrderByCreatedAtDesc(user: PortalUserEntity): Optional<PortalLoginChallengeEntity>
}

@Repository
interface PortalBankLoginChallengeRepository : JpaRepository<PortalBankLoginChallengeEntity, String> {
    @Query(
        """
        SELECT c FROM PortalBankLoginChallengeEntity c
        WHERE c.id = :challengeId
          AND c.identity.id = :identityId
        """
    )
    fun findForIdentityByChallengeId(
        @Param("challengeId") challengeId: String,
        @Param("identityId") identityId: Long
    ): Optional<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT c FROM PortalBankLoginChallengeEntity c
        WHERE c.identity.id = :identityId
          AND (:status IS NULL OR c.status = :status)
        ORDER BY c.createdAt DESC
        """
    )
    fun findForIdentity(
        @Param("identityId") identityId: Long,
        @Param("status") status: BankLoginChallengeStatus?,
        pageable: Pageable
    ): Page<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT c FROM PortalBankLoginChallengeEntity c
        WHERE c.identity.id = :identityId
          AND c.status = :pendingStatus
          AND c.expiresAt > :now
        ORDER BY c.createdAt DESC
        """
    )
    fun findActivePendingForIdentity(
        @Param("identityId") identityId: Long,
        @Param("pendingStatus") pendingStatus: BankLoginChallengeStatus,
        @Param("now") now: Instant,
        pageable: Pageable
    ): Page<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT c FROM PortalBankLoginChallengeEntity c
        WHERE c.identity.id = :identityId
          AND c.status = :pendingStatus
          AND c.expiresAt <= :now
        ORDER BY c.createdAt DESC
        """
    )
    fun findExpiredForIdentity(
        @Param("identityId") identityId: Long,
        @Param("pendingStatus") pendingStatus: BankLoginChallengeStatus,
        @Param("now") now: Instant,
        pageable: Pageable
    ): Page<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT DISTINCT c FROM PortalBankLoginChallengeEntity c
        JOIN c.identity i
        JOIN i.linkedAccounts a
        WHERE c.id = :challengeId
          AND a.bankHandle = :bankHandle
        """
    )
    fun findForBankByChallengeId(
        @Param("challengeId") challengeId: String,
        @Param("bankHandle") bankHandle: String
    ): Optional<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT c FROM PortalBankLoginChallengeEntity c
        JOIN c.identity i
        JOIN i.linkedAccounts a
        WHERE c.status = :status
          AND c.expiresAt > :now
          AND a.bankHandle = :bankHandle
          AND a.bankCustomerRef = :bankCustomerRef
        ORDER BY c.createdAt DESC
        """
    )
    fun findPendingForBankCustomer(
        @Param("bankHandle") bankHandle: String,
        @Param("bankCustomerRef") bankCustomerRef: String,
        @Param("status") status: BankLoginChallengeStatus,
        @Param("now") now: Instant
    ): List<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT DISTINCT c FROM PortalBankLoginChallengeEntity c
        JOIN c.identity i
        JOIN i.linkedAccounts a
        WHERE a.bankHandle = :bankHandle
          AND (:status IS NULL OR c.status = :status)
          AND (
            :needle IS NULL OR
            LOWER(CAST(i.nptHandle AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
            LOWER(CAST(COALESCE(a.bankCustomerRef, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
            LOWER(CAST(COALESCE(c.identifierHint, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%')
          )
        ORDER BY c.createdAt DESC
        """
    )
    fun findForBank(
        @Param("bankHandle") bankHandle: String,
        @Param("status") status: BankLoginChallengeStatus?,
        @Param("needle") needle: String?
    ): List<PortalBankLoginChallengeEntity>

    @Query(
        """
        SELECT COUNT(c) FROM PortalBankLoginChallengeEntity c
        WHERE c.status = :status
          AND c.expiresAt > :now
        """
    )
    fun countByStatusAndExpiresAtAfter(
        @Param("status") status: BankLoginChallengeStatus,
        @Param("now") now: Instant
    ): Long
}

@Repository
interface PortalEmailOtpRepository : JpaRepository<PortalEmailOtpEntity, Long> {
    fun findTopByUserAndPurposeOrderByCreatedAtDesc(user: PortalUserEntity, purpose: String): Optional<PortalEmailOtpEntity>
}

@Repository
interface PortalUserPasskeyRepository : JpaRepository<PortalUserPasskeyEntity, Long> {
    fun findAllByUser(user: PortalUserEntity): List<PortalUserPasskeyEntity>
    fun findByCredentialId(credentialId: String): Optional<PortalUserPasskeyEntity>

    @Query("SELECT p FROM PortalUserPasskeyEntity p JOIN FETCH p.user WHERE p.credentialId = :credentialId")
    fun findByCredentialIdWithUser(credentialId: String): Optional<PortalUserPasskeyEntity>
}

@Repository
interface PortalWebAuthnChallengeRepository : JpaRepository<PortalWebAuthnChallengeEntity, Long> {
    fun findByChallenge(challenge: String): Optional<PortalWebAuthnChallengeEntity>
}

@Repository
interface IdentityRepository : JpaRepository<IdentityEntity, Long> {
    fun findByNptHandle(handle: String): IdentityEntity?
    fun findByNationalId(nationalId: String): IdentityEntity?
    fun findByPhone(phone: String): IdentityEntity?
    fun existsByNptHandle(handle: String): Boolean
    fun countByStatus(status: IdentityStatus): Long
    fun countByDefaultBankHandleIsNullAndStatus(status: IdentityStatus): Long
    fun countByStatusNot(status: ly.openwave.identity.entity.IdentityStatus): Long

    @Query(
        value = """
            SELECT DISTINCT i FROM IdentityEntity i
            JOIN i.linkedAccounts a
            WHERE a.bankHandle = :bankHandle
              AND (:activeOnly = false OR i.status = :activeStatus)
              AND (
                :needle IS NULL OR
                LOWER(CAST(i.nptHandle AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(i.displayName AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.bankCustomerRef, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.displayName, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.iban, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%')
              )
            """,
        countQuery = """
            SELECT COUNT(DISTINCT i) FROM IdentityEntity i
            JOIN i.linkedAccounts a
            WHERE a.bankHandle = :bankHandle
              AND (:activeOnly = false OR i.status = :activeStatus)
              AND (
                :needle IS NULL OR
                LOWER(CAST(i.nptHandle AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(i.displayName AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.bankCustomerRef, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.displayName, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%') OR
                LOWER(CAST(COALESCE(a.iban, '') AS string)) LIKE CONCAT('%', CAST(:needle AS string), '%')
              )
            """
    )
    fun searchAliasesForBank(
        @Param("bankHandle") bankHandle: String,
        @Param("activeOnly") activeOnly: Boolean,
        @Param("activeStatus") activeStatus: IdentityStatus,
        @Param("needle") needle: String?,
        pageable: Pageable
    ): Page<IdentityEntity>
}

@Repository
interface LinkedAccountRepository : JpaRepository<LinkedAccountEntity, Long> {
    fun findAllByIdentityIdAndBankHandle(identityId: Long, bankHandle: String): List<LinkedAccountEntity>
    fun findAllByBankHandle(bankHandle: String): List<LinkedAccountEntity>
    fun findByIdAndBankHandle(id: Long, bankHandle: String): LinkedAccountEntity?
    fun findByIdentityIdAndBankHandleAndIsDefaultTrue(identityId: Long, bankHandle: String): LinkedAccountEntity?
    fun findByIdentityIdAndIban(identityId: Long, iban: String): LinkedAccountEntity?
    fun existsByIdentityIdAndIban(identityId: Long, iban: String): Boolean
    fun findAllByIdentityId(identityId: Long): List<LinkedAccountEntity>
    fun existsByIdentityIdAndBankHandle(identityId: Long, bankHandle: String): Boolean
    fun existsByIdentityIdAndBankHandleAndBankCustomerRef(identityId: Long, bankHandle: String, bankCustomerRef: String): Boolean
    fun countByIsDefaultTrue(): Long

    @Modifying
    @Query("UPDATE LinkedAccountEntity l SET l.isDefault = false WHERE l.identity.id = :identityId AND l.bankHandle = :bankHandle")
    fun clearBankDefaults(identityId: Long, bankHandle: String)

    @Modifying
    @Query("UPDATE LinkedAccountEntity l SET l.updatedAt = :now WHERE l.id = :id")
    fun touchUpdatedAt(id: Long, now: Instant)
}


/**
 * Handles that used to belong to somebody and never will again.
 *
 * Consulted by every "is this name free" check. A retired handle is NOT free —
 * that is the entire reservation, and the only thing standing between a renamed
 * customer's old payees and a stranger's account.
 */
interface RetiredHandleRepository : org.springframework.data.jpa.repository.JpaRepository<ly.openwave.identity.entity.RetiredHandleEntity, Long> {
    fun findByHandle(handle: String): ly.openwave.identity.entity.RetiredHandleEntity?
    fun existsByHandle(handle: String): Boolean
    fun findAllByFormerIdentityId(formerIdentityId: Long): List<ly.openwave.identity.entity.RetiredHandleEntity>
}

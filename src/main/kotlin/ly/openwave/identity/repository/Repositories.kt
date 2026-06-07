package ly.openwave.identity.repository

import ly.openwave.identity.entity.BankEntity
import ly.openwave.identity.entity.IdentityEntity
import ly.openwave.identity.entity.IdentityStatus
import ly.openwave.identity.entity.LinkedAccountEntity
import ly.openwave.identity.entity.PortalEmailOtpEntity
import ly.openwave.identity.entity.PortalAuditEventEntity
import ly.openwave.identity.entity.PortalUserEntity
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
    fun findAllByBankHandle(bankHandle: String): List<PortalUserEntity>
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
    fun countByStatusNot(status: ly.openwave.identity.entity.IdentityStatus): Long

    @Query(
        value = """
            SELECT DISTINCT i FROM IdentityEntity i
            JOIN i.linkedAccounts a
            WHERE a.bankHandle = :bankHandle
              AND (:activeOnly = false OR i.status = :activeStatus)
              AND (
                :needle IS NULL OR
                LOWER(i.nptHandle) LIKE CONCAT('%', :needle, '%') OR
                LOWER(i.displayName) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.bankCustomerRef, '')) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.displayName, '')) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.iban, '')) LIKE CONCAT('%', :needle, '%')
              )
            """,
        countQuery = """
            SELECT COUNT(DISTINCT i) FROM IdentityEntity i
            JOIN i.linkedAccounts a
            WHERE a.bankHandle = :bankHandle
              AND (:activeOnly = false OR i.status = :activeStatus)
              AND (
                :needle IS NULL OR
                LOWER(i.nptHandle) LIKE CONCAT('%', :needle, '%') OR
                LOWER(i.displayName) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.bankCustomerRef, '')) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.displayName, '')) LIKE CONCAT('%', :needle, '%') OR
                LOWER(COALESCE(a.iban, '')) LIKE CONCAT('%', :needle, '%')
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

    @Modifying
    @Query("UPDATE LinkedAccountEntity l SET l.isDefault = false WHERE l.identity.id = :identityId AND l.bankHandle = :bankHandle")
    fun clearBankDefaults(identityId: Long, bankHandle: String)

    @Modifying
    @Query("UPDATE LinkedAccountEntity l SET l.updatedAt = :now WHERE l.id = :id")
    fun touchUpdatedAt(id: Long, now: Instant)
}
